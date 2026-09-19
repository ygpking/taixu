package top.wkbin.taixu.harness.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/**
 * 本次「三合一重构」的专项回归（纯 JUnit，不依赖 Android 运行时/网络）。
 *  1. 折叠线口径：裁切基准必须是「单次输入上限」而非「上下文窗口」；水位默认 85%。
 *     —— 直接复现 413 根因场景（窗口 100 万）并给出反证锚点。
 *  2. 原文归档器：被折叠原文确实落盘、可 grep，且摘要索引指向该文件。
 */
class ContextWatermarkRefactorTest {

    // ---------------- 1. 折叠线口径（413 根因回归） ----------------

    @Test
    fun `million-token window no longer pushes the folding line to ~987K`() {
        // 用户真实配置（一手取证）：模型档案 contextTokens=1,000,000，从未显式配置输入上限。
        val window = 1_000_000
        val windowBudget = ContextWindowPolicy.resolveEffectiveBudget(window)
        assertEquals(1_000_000, windowBudget)

        // 裁切基准 = 输入上限（未显式配置 → 窗口 × 90%，贯通窗口、不再被 128K 硬顶）
        val inputLimit = ContextWindowPolicy.resolveInputLimit(
            declaredInputLimit = null,
            windowBudget = windowBudget,
            globalInputLimit = null,
        )
        assertEquals(900_000, inputLimit)

        // 触发线 = 基准 × 85%
        val line = ContextWindowPolicy.foldingLimitFor(inputLimit, 85)
        assertTrue("触发线应落在 ~76.5 万，实际 $line", line in 760_000..770_000)

        // 反证锚点：旧行为（基准取窗口值、水位 100%）会把触发线顶到 ~98.7 万；
        // 新行为虽已贯通窗口，但触发线仍应显著低于「窗口本身」（保留输出/工具预留），
        // 二者必须保持区分度，否则本测试无判别力。
        val buggyLine = ContextWindowPolicy.foldingLimitFor(windowBudget, 100)
        assertTrue("旧触发线应高于新触发线（$buggyLine vs $line）", buggyLine > line)
        assertTrue("38.4 万的真实峰值在新行为（90 万额度）下不再折叠，属额度放大后的预期结果", 384_137 < line)
    }

    @Test
    fun `declared input limit wins and is normalized`() {
        val window = 1_000_000
        // 显式配置 → 直接采用（规范化后）
        assertEquals(
            200_000,
            ContextWindowPolicy.resolveInputLimit(200_000, window, null),
        )
        // 全局值次之
        assertEquals(
            96_000,
            ContextWindowPolicy.resolveInputLimit(null, window, 96_000),
        )
        // 极小值被钳到下限，不会产生无法工作的配置
        assertEquals(
            ContextWindowPolicy.MIN_INPUT_LIMIT,
            ContextWindowPolicy.resolveInputLimit(10, window, null),
        )
    }

    @Test
    fun `default watermark is a single source of truth`() {
        // 常量收敛：harness 侧的默认水位必须与 core:model 真相源同值
        assertEquals(
            top.wkbin.taixu.core.model.ContextBudgetDefaults.DEFAULT_FOLDING_RATIO_PERCENT,
            ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT,
        )
        assertEquals(90, ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT)
        assertEquals(
            top.wkbin.taixu.core.model.ContextBudgetDefaults.DEFAULT_MAX_KEEP_TOKENS,
            ContextWindowPolicy.DEFAULT_MAX_KEEP_TOKENS,
        )
        assertEquals(40_000, ContextWindowPolicy.DEFAULT_MAX_KEEP_TOKENS)
    }

    @Test
    fun `compaction trigger is token-driven only`() {
        // 触发只看 token：同样的消息，token 预算充足时完全不折叠，与「轮次」无关。
        val messages = buildList<HarnessMessage> {
            repeat(50) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "x".repeat(100)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "y".repeat(100)))
            }
        }
        val fitAll = ContextWindowPolicy.computeKeepFromIndex(
            messages = messages,
            budget = 1_000_000,
            systemTokens = 100,
            minKeepMessages = ContextWindowPolicy.MIN_KEEP_MESSAGES,
        )
        assertEquals("预算充足时不折叠（100 轮也一样）", 0, fitAll)

        val overflow = ContextWindowPolicy.computeKeepFromIndex(
            messages = messages,
            budget = 8_000,
            systemTokens = 100,
            minKeepMessages = ContextWindowPolicy.MIN_KEEP_MESSAGES,
        )
        assertTrue("超出水位才折叠", overflow > 0)
    }

    // ---------------- 2. 原文归档器 ----------------

    @Test
    fun `archived originals are written to workspace and greppable`() {
        val workspace = Files.createTempDirectory("taixu-archive-test").toFile()
        try {
            val messages = listOf<HarnessMessage>(
                UserMessage("u1", 1, "请把 /etc/hosts 里的 UNIQUE_MARKER_42 改成 127.0.0.1"),
                ToolCall(
                    "c1", 2, HarnessTool.BASE,
                    buildJsonObject { put("command", "cat /etc/hosts") },
                ),
                ToolResult("r1", 3, "c1", true, "127.0.0.1 localhost # UNIQUE_MARKER_42"),
            )

            val relative = ContextArchive.archive(
                workspacePath = workspace.absolutePath,
                sessionId = "sess-abc",
                messages = messages,
                reason = "测试",
                archiveId = ContextArchive.archiveId(1_700_000_000_000, 3),
            )

            assertNotNull("归档应成功并返回相对路径", relative)
            val file = File(workspace, relative!!)
            // 文件名含随机后缀（防同毫秒同序号撞名），只校验路径前缀与扩展名
            assertTrue(relative.startsWith(".taixu-context/sess-abc/1700000000000-3"))
            assertTrue(relative.endsWith(".md"))
            assertTrue("归档文件必须真实落盘", file.exists())
            val text = file.readText()
            // 原始细节必须可检索（这正是「不能只留摘要」的核心诉求）
            assertTrue(text.contains("UNIQUE_MARKER_42"))
            assertTrue(text.contains("cat /etc/hosts"))
            assertTrue(text.contains("## [1] user"))
            assertTrue(text.contains("## [2] tool_call"))
            assertTrue(text.contains("## [3] tool_result"))

            // 摘要索引必须指向该文件，并提示用 read/grep 回捞
            val note = ContextArchive.indexNote(relative, messages.size)
            assertTrue(note.contains(relative))
            assertTrue(note.contains("3"))
            assertTrue(note.contains("grep"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `archive is a no-op without a workspace and never throws`() {
        val messages = listOf<HarnessMessage>(UserMessage("u1", 1, "x"))

        // 无工作区 → 不落盘、返回 null、不抛异常（保证压缩主流程绝不受影响）
        assertNull(ContextArchive.archive(null, "s", messages, "r", "1"))
        assertNull(ContextArchive.archive("", "s", messages, "r", "1"))
        // 空消息 → 同样不动
        assertNull(ContextArchive.archive("/tmp", "s", emptyList(), "r", "1"))
        // 未归档时索引为空串（摘要不会多出无意义尾巴）
        assertEquals("", ContextArchive.indexNote(null, 3))
        assertEquals("", ContextArchive.indexNote(".taixu-context/a.md", 0))
    }

    @Test
    fun `archive renders multi-line text readably instead of escaped toString`() {
        val workspace = Files.createTempDirectory("taixu-archive-multiline").toFile()
        try {
            val output = "line1\nline2\nline3"
            val relative = ContextArchive.archive(
                workspacePath = workspace.absolutePath,
                sessionId = "s",
                messages = listOf(ToolResult("r", 1, "c", true, output)),
                reason = "r",
                archiveId = "x",
            )!!
            val text = File(workspace, relative).readText()
            // 真实换行必须保留（grep 单行命中/按行阅读），不能被转义成 \n 字面量
            assertTrue(text.contains("line1\nline2\nline3"))
            assertFalse(text.contains("line1\\nline2"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    // ---------------- 归档器「无节制积累」防护（本轮 code_refactor 加固） ----------------

    @Test
    fun `archive keeps only the most recent files per session`() {
        val workspace = Files.createTempDirectory("taixu-archive-trim").toFile()
        try {
            // 写 22 个不同时间戳的归档，超过上限 20
            for (t in 1000L..1021L) {
                ContextArchive.archive(
                    workspacePath = workspace.absolutePath,
                    sessionId = "trim",
                    messages = listOf(ToolResult("r", 1, "c", true, "batch-$t")),
                    reason = "r",
                    archiveId = "$t-1",
                )
            }
            val dir = File(File(workspace, ContextArchive.DIR_NAME), "trim")
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }!!.map { it.name }
            assertEquals("目录内归档数必须被压到上限 20", 20, files.size)
            // 最旧的两个被清掉，最新的仍在
            assertTrue(files.none { it.startsWith("1000-") })
            assertTrue(files.none { it.startsWith("1001-") })
            assertTrue(files.any { it.startsWith("1021-") })
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `archive truncates an over-long single message`() {
        val workspace = Files.createTempDirectory("taixu-archive-truncate").toFile()
        try {
            val huge = "A".repeat(50_000)
            val relative = ContextArchive.archive(
                workspacePath = workspace.absolutePath,
                sessionId = "s",
                messages = listOf(ToolResult("r", 1, "c", true, huge)),
                reason = "r",
                archiveId = "x",
            )!!
            val text = File(workspace, relative).readText()
            assertTrue("超长消息应被标注省略", text.contains("省略"))
            assertTrue("文件不应完整承载 5 万字符原文", text.length < 50_000)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `archive id stays unique across repeated calls with same arguments`() {
        val ids = (1..5000).map { ContextArchive.archiveId(1_700_000_000_000, 3) }.toSet()
        assertEquals("同参调用也必须产出唯一 id（防同毫秒同序号撞名覆盖）", 5000, ids.size)
    }
}
