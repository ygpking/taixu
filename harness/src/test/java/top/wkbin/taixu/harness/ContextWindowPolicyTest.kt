package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.put

class ContextWindowPolicyTest {
    @Test
    fun `generated image payload is omitted from provider context and token estimate`() {
        val payload = "A".repeat(1_000_000)
        val raw = "完成：![图](data:image/png;base64,$payload) 请继续"

        val sanitized = ContextWindowPolicy.assistantTextForContext(raw)

        assertTrue(sanitized.contains("生成了一张图片"))
        assertTrue(sanitized.contains("请继续"))
        assertFalse(sanitized.contains(payload.take(64)))
        assertTrue(ContextWindowPolicy.estimateTokens(sanitized) < 100)
    }
    @Test
    fun keepsRecentMessagesWithinBudget() {
        val messages = listOf(
            UserMessage("1", 1, "a".repeat(2_000)),
            AssistantText("2", 2, "b".repeat(2_000)),
            UserMessage("3", 3, "recent"),
        )

        // 新契约：预算充足（消息数 < MIN_KEEP_MESSAGES=10 且未超折叠线）时，保留最近 10 条
        // → 3 条全部保留（keepFrom=0），保证「填多少、保多少」，不再只留 1~2 条导致失忆。
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        assertEquals(0, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `oversized history collapses to the most recent MIN_KEEP_MESSAGES turns`() {
        // 新契约的另一面：历史远超保留下限且预算紧张时，必须收敛到最近 MIN_KEEP_MESSAGES 条，
        // 不能把整段历史都塞进上下文撑爆模型。
        val big = "x".repeat(20_000)
        val messages = (1..30).map { index ->
            if (index % 2 == 0) AssistantText("a$index", index, big) else UserMessage("u$index", index, big)
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)

        // 保留下来的条数不应超过 MIN_KEEP_MESSAGES（允许对齐工具对/用户轮而略少），
        // 且必须留住最后一条。
        val kept = messages.size - keepFrom
        assertTrue("保留条数 $kept 应 <= ${ContextWindowPolicy.MIN_KEEP_MESSAGES}", kept <= ContextWindowPolicy.MIN_KEEP_MESSAGES)
        assertTrue("必须保留最后一条消息", keepFrom < messages.size)
    }

    @Test
    fun `exhausted budget retains only a minimal recent turn instead of all history`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            AssistantText("old-assistant", 2, "old answer"),
            UserMessage("latest-user", 3, "latest request"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 4_000, systemTokens = 4_000)

        assertEquals(2, keepFrom)
    }

    @Test
    fun `exhausted budget does not orphan the only tool result`() {
        val call = ToolCall("call", 1, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {})
        val messages = listOf(call, ToolResult("result", 2, "call", true, "ok"))

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)

        assertEquals(0, keepFrom)
    }

    @Test
    fun `oversized system prompt is bounded`() {
        val fitted = ContextWindowPolicy.fitSystemPrompt("x".repeat(100_000), budget = 8_000)

        assertTrue(fitted.length < 100_000)
        assertTrue(fitted.contains("系统提示因上下文预算受限已截断"))
    }

    @Test
    fun compactsLongToolOutputWithHeadAndTail() {
        val compacted = ContextWindowPolicy.compactToolOutput(
            toolName = null,
            args = null,
            output = (1..10).joinToString("\n") { "line-$it" },
            success = true,
        )

        assertTrue(compacted.contains("line-1"))
        assertTrue(compacted.contains("line-10"))
        assertTrue(compacted.contains("已略去 5 行"))
    }

    @Test
    fun effectiveUsageReplacesCollapsedHistoryWithOneBoundedSummary() {
        val messages = buildList<HarnessMessage> {
            repeat(100) { index ->
                add(UserMessage("u-$index", index * 2L, "需求-$index " + "a".repeat(500)))
                add(AssistantText("a-$index", index * 2L + 1, "回复-$index " + "b".repeat(500)))
            }
            add(UserMessage("latest", 1_000, "最近问题"))
        }

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 18_000,
            systemTokens = 100,
            compactionEnabled = true,
        )

        assertTrue(usage.keepFromIndex > 0)
        assertTrue(usage.totalTokens < 18_000)
        assertTrue(usage.conversationTokens < messages.sumOf {
            when (it) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(it.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(it.text)
                else -> 0
            }
        })
    }

    @Test
    fun `large context models keep all history that fits the token budget`() {
        val messages = buildList<HarnessMessage> {
            repeat(30) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index"))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index"))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages = messages,
            budget = 1_000_000,
            systemTokens = 0,
        )

        assertEquals(0, keepFrom)
    }

    @Test
    fun `budget-constrained windows still start on a complete user turn`() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val messages = listOf(
            UserMessage("old", 1, "x".repeat(1_000)),
            call,
            ToolResult("result", 3, "call", true, "ok"),
            UserMessage("latest", 4, "now"),
        )

        // 新契约：本例消息数 4 < 10 且未超折叠线 → 全部保留。
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)

        assertEquals(0, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `budget-constrained window never starts mid tool-pair`() {
        // 构造一段超长历史，强制触发折叠，验证窗口边界仍落在 user 轮上，
        // 且不会把 ToolResult 切离它的 ToolCall。
        val filler = "y".repeat(30_000)
        val messages = buildList {
            for (i in 1..20) {
                add(UserMessage("u$i", i, filler))
                add(AssistantText("a$i", i, filler))
            }
            add(UserMessage("old", 41, "old turn"))
            add(
                ToolCall(
                    "call",
                    42,
                    HarnessTool.BASE,
                    kotlinx.serialization.json.buildJsonObject {},
                    reasoning = "x".repeat(4_000),
                )
            )
            add(ToolResult("result", 43, "call", true, "ok"))
            add(UserMessage("latest", 44, "now"))
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        val kept = messages.drop(keepFrom)

        assertTrue("窗口首条应为 user 轮", kept.first() is UserMessage)
        val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }
        assertTrue(
            "被保留的 ToolResult 必须能对上同窗口内的 ToolCall",
            kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds }
        )
    }

    @Test
    fun `parallel tool calls and results remain paired across a forced boundary`() {
        val call1 = ToolCall(
            "call-1",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val call2 = ToolCall("call-2", 3, HarnessTool.READ, kotlinx.serialization.json.buildJsonObject {})
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            call1,
            call2,
            ToolResult("result-1", 4, "call-1", true, "first"),
            ToolResult("result-2", 5, "call-2", true, "second"),
            AssistantText("old-answer", 6, "done"),
            UserMessage("latest-user", 7, "latest request"),
        )

        // 新契约：消息数 7 < MIN_KEEP_MESSAGES=10 且未超折叠线 → 全部保留，工具对天然闭合。
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        val kept = messages.drop(keepFrom)
        val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }

        assertEquals(0, keepFrom)
        assertTrue(kept.first() is UserMessage)
        assertTrue(kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds })
    }

    @Test
    fun `interrupted cross-turn tool result pulls its call back into the window`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            ToolCall("call", 2, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}),
            UserMessage("latest-user", 3, "latest request"),
            ToolResult("late-result", 4, "call", true, "late"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)
        val kept = messages.drop(keepFrom)

        assertEquals(1, keepFrom)
        assertTrue(kept.first() is ToolCall)
        assertTrue(kept.filterIsInstance<ToolResult>().all { result ->
            kept.filterIsInstance<ToolCall>().any { it.id == result.toolCallId }
        })
    }

    @Test
    fun historySummaryKeepsStructuredFactsAndToolSpecificFailureContext() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject { put("command", "./gradlew test") },
        )
        val summary = ContextWindowPolicy.buildHistorySummary(
            listOf(
                UserMessage("u", 1, "必须保持 core 模块纯 Kotlin，不能引入 Android 依赖"),
                AssistantText("a", 2, "决定采用滑动窗口方案，修改 /workspace/app/src/Main.kt"),
                call,
                ToolResult("r", 4, "call", false, "FAILURE: tests failed with a stack trace"),
            ),
        )

        assertTrue(summary.contains("用户硬约束"))
        assertTrue(summary.contains("关键决定"))
        assertTrue(summary.contains("涉及文件"))
        assertTrue(summary.contains("失败根因线索"))
        assertTrue(summary.contains("gradlew"))
    }

    @Test
    fun `estimateEffectiveUsage calculates 8-dimensional breakdown correctly`() {
        val messages = listOf(
            UserMessage("u1", 1, "Hello world"),
            AssistantText("a1", 2, "I will check the files."),
            ToolCall("t1", 3, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {
                put("command", "ls -la")
            }),
            ToolResult("r1", 4, "t1", true, "file1.txt\nfile2.txt"),
        )

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 128_000,
            systemTokens = 5_000,
            compactionEnabled = true,
            systemPromptTokens = 576,
            toolDefinitionTokens = 3_600,
            rulesTokens = 1_400,
            skillsTokens = 500,
            mcpTokens = 800,
            subagentTokens = 1_100,
        )

        val bd = usage.breakdown
        assertEquals(576, bd.systemPromptTokens)
        assertEquals(3_600, bd.toolDefinitionTokens)
        assertEquals(1_400, bd.rulesTokens)
        assertEquals(500, bd.skillsTokens)
        assertEquals(800, bd.mcpTokens)
        assertEquals(1_100, bd.subagentTokens)
        assertEquals(0, bd.summarizedTokens)
        assertTrue(bd.conversationTokens > 0)
        assertEquals(bd.totalTokens, usage.totalTokens)
    }
}
