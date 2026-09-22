package top.wkbin.taixu.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.AgentSubagentRepository
import top.wkbin.taixu.core.database.AgencyAgentCatalogLoader
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.RoomAgentContextRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.sanitizeApiTranscript
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.core.tools.ToolRepository
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.prompt.PrivilegeSectionRenderer
import top.wkbin.taixu.harness.prompt.PromptAssetLoader
import top.wkbin.taixu.harness.prompt.PromptRouter
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder

/**
 * API 上下文组装器全栈集成测试：真实 Room（会话树 + 压缩树）+ 真实 DataStore 偏好 +
 * 打包内真实提示词资产。验证 NATIVE / JSON_TEXT 双协议、视觉剥离、思考回传标记、
 * 能力事件剔除、未应答调用的丢弃与压缩摘要注入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiContextAssemblerTest {

    private lateinit var database: AppDatabase
    private lateinit var store: top.wkbin.taixu.harness.session.SessionTreeStore
    private lateinit var assembler: ApiContextAssembler
    private lateinit var compactionManager: CompactionManager
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "taixu-assembler-${System.nanoTime()}")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val json = Json { ignoreUnknownKeys = true }
        val agentPrefs = AgentPreferences(SettingsDataStore(context, SecretManager()))

        compactionManager = CompactionManager(runtimeRepo, json)
        store = top.wkbin.taixu.harness.session.SessionTreeStore(runtimeRepo, json, logger)

        val promptAssets = PromptAssetLoader(context)
        val builder = SystemPromptBuilder(
            context = context,
            settingsDataStore = agentPrefs,
            skillRepository = AgentSkillRepository(database.agentSkillDao()),
            toolRepository = ToolRepository(database.toolDao(), ToolRegistry(context, OkHttpClient(), logger)),
            agentContextDao = RoomAgentContextRepository(database.agentContextDao()),
            subagentRepository = AgentSubagentRepository(
                database.agentSubagentDao(),
                AgencyAgentCatalogLoader(context, json),
            ),
            mcpServerRepository = McpServerRepository(database.mcpServerDao(), SecretManager()),
            promptAssets = promptAssets,
            fileAccess = WorkspaceFileAccess(tempDir),
            privilegeRenderer = PrivilegeSectionRenderer { "" },
            promptRouter = PromptRouter(promptAssets),
        )
        assembler = ApiContextAssembler(
            top.wkbin.taixu.harness.budget.ContextBudgetResolver(agentPrefs),
            compactionManager,
            agentPrefs,
            builder,
        )
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun nativeModel(vision: Boolean = false, tokens: Int = 200_000) = ModelConfig(
        name = "n", provider = "p", model = "gpt-x",
        baseUrl = "https://example.com", apiKey = null,
        contextTokens = tokens,
        visionEnabled = vision,
    )

    private suspend fun push(sessionId: String, vararg messages: top.wkbin.taixu.harness.HarnessMessage) {
        messages.forEach { store.append(sessionId, it) }
    }

    private fun jsonTextModel(vision: Boolean = false) =
        nativeModel(vision).copy(toolCallMode = ToolCallMode.JSON_TEXT)

    // ---------- NATIVE 协议 ----------

    @Test
    fun `native injects system prompt and maps user and plain assistant`() = runBlocking {
        push(
            "s-native",
            UserMessage("u1", 1L, "看看文件"),
            AssistantText("a0", 2L, "好的"),
        )
        val out = assembler.assemble("s-native", nativeModel(), workspacePath = "")

        assertEquals("system", out[0].role)
        val systemPrompt = out[0].content!!
        assertTrue(systemPrompt.isNotBlank())
        assertEquals(9, Regex("department=\\\"").findAll(systemPrompt).count())
        assertFalse(systemPrompt.contains("agency_engineering_frontend_developer"))
        assertFalse(systemPrompt.contains("Frontend Developer"))
        assertEquals(2, out.size - 1)
        assertEquals("user", out[1].role)
        assertEquals("assistant", out[2].role)
        assertNull(out[2].tool_calls)
    }

    @Test
    fun `native pairs answered tool calls onto assistant and emits tool role`() = runBlocking {
        val call = ToolCall(
            id = "t1", createdAt = 3L, tool = HarnessTool.READ,
            args = buildJsonObject { }, rawToolName = "read",
        )
        push(
            "s-pair",
            UserMessage("u1", 1L, "读一下"),
            AssistantText("a0", 2L, "我来读"),
            call,
            ToolResult("r1", 4L, "t1", success = true, output = "内容"),
            AssistantText("a1", 5L, "结论"),
        )
        val out = assembler.assemble("s-pair", nativeModel(), "")

        val paired = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals(listOf("t1"), paired.tool_calls!!.map { it.id })
        val toolMsg = out.last { it.role == "tool" }
        assertEquals("t1", toolMsg.tool_call_id)
        assertEquals("内容", toolMsg.content)
        assertEquals("结论", out.last { it.role == "assistant" }.content)
    }

    @Test
    fun `unanswered native tool calls are dropped silently`() = runBlocking {
        push(
            "s-orphan",
            UserMessage("u1", 1L, "x"),
            ToolCall("t9", 3L, HarnessTool.READ, buildJsonObject { }),
        )
        val out = assembler.assemble("s-orphan", nativeModel(vision = false), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })
    }

    @Test
    fun `historical assistant messages do not leak reasoning content to prevent reasoning loop`() = runBlocking {
        push("s-think", UserMessage("u1", 1L, "hi"), AssistantText("a1", 2L, "<think>思考中</think>hello", reasoning = "历史思考"))
        val out = assembler.assemble("s-think", nativeModel(), "", thinkingMode = true)
        val assistant = out.last { it.role == "assistant" }
        assertNull(assistant.reasoning_content)
        assertEquals("hello", assistant.content)

        val off = assembler.assemble("s-think", nativeModel(), "", thinkingMode = false)
        assertNull(off.last { it.role == "assistant" }.reasoning_content)
        assertEquals("hello", off.last { it.role == "assistant" }.content)
    }

    @Test
    fun `tool-calling assistant messages must round-trip reasoning content for thinking mode`() = runBlocking {
        // DeepSeek 思考模式：两个 user 之间若有工具调用，中间 assistant 消息的
        // reasoning_content 必须原样传回，否则 400 报错。
        push(
            "s-think-tool",
            UserMessage("u1", 1L, "读文件"),
            ToolCall("t1", 2L, HarnessTool.READ, buildJsonObject { }, reasoning = "需要先看文件", rawToolName = "read"),
            ToolResult("r1", 3L, "t1", success = true, output = "内容"),
            AssistantText("a1", 4L, "文件内容是……", reasoning = "已读到"),
        )
        val out = assembler.assemble("s-think-tool", nativeModel(), "")
        val toolTurn = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals("需要先看文件", toolTurn.reasoning_content)
        // 收尾纯文本 assistant 轮仍不回传
        assertNull(out.last { it.role == "assistant" && it.tool_calls == null }.reasoning_content)
    }

    // ---------- JSON_TEXT 协议 ----------

    @Test
    fun `json text converts tool results into user text messages`() = runBlocking {
        val call = ToolCall(id = "j1", createdAt = 3L, tool = HarnessTool.BASE, args = buildJsonObject { }, rawToolName = "base")
        push(
            "s-json",
            UserMessage("u1", 1L, "跑命令"),
            AssistantText("a0", 2L, "模型叙述文本"),
            call,
            ToolResult("r1", 4L, "j1", success = true, output = "输出内容"),
        )
        val out = assembler.assemble("s-json", jsonTextModel(), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })

        val asUser = out.filter { it.role == "user" }.last()
        assertTrue(asUser.content!!.contains("【工具 base 执行结果·成功】"))
        assertTrue(asUser.content!!.contains("输出内容"))

        val narration = out.first { it.role == "assistant" }
        assertEquals("模型叙述文本", narration.content)
    }

    // ---------- 视觉与能力事件 ----------

    @Test
    fun `images are stripped when vision disabled and kept when enabled`() = runBlocking {
        val images = listOf("https://img.example/a.png")
        push("s-vision", UserMessage("u1", 1L, "看图", imageUrls = images))
        val stripped = assembler.assemble("s-vision", nativeModel(vision = false), "")
        assertTrue(stripped.first { it.role == "user" }.imageUrls.isEmpty())

        val kept = assembler.assemble("s-vision", nativeModel(vision = true), "")
        assertEquals(images, kept.first { it.role == "user" }.imageUrls)
    }

    @Test
    fun `capability events never reach the provider payload`() = runBlocking {
        push(
            "s-cap",
            UserMessage("u1", 1L, "@skill"),
            CapabilityEvent("cap1", 2L, CapabilityEvent.Kind.SKILL, "技能", "详情"),
        )
        val out = assembler.assemble("s-cap", nativeModel(), "")
        assertFalse(out.any { it.content?.contains("详情") == true })
        // 判别力补强：原断言是「content 不含某文本」，而 UI-only 消息被误映射成
        // role=system、content=null 时该断言恒真（null?.contains(...) == false）。
        // 改为同时钉住消息条数与角色序列——任何"多出来一条"都会被抓到。
        assertEquals("system + user 两条，不应多出能力事件", 2, out.size)
        assertEquals(listOf("system", "user"), out.map { it.role })
    }

    /**
     * 回归（P1）：SkillSuggestion 的契约是「绝不发给模型」，但组装器的跳过清单原先只有
     * CapabilityEvent / ModelSwitchEvent，它经 HarnessApiMapper 被映射成
     * `role=system, content=null` 的畸形消息；OpenAI 兼容路径序列化时连 content 键都不写，
     * 严格端点直接 400，且该消息留在转写里污染后续每一轮。
     */
    @Test
    fun `skill suggestions never reach the provider payload in native mode`() = runBlocking {
        push(
            "s-suggest-native",
            UserMessage("u1", 1L, "帮我整理周报"),
            AssistantText("a1", 2L, "好的"),
            SkillSuggestion(
                id = "sg1",
                createdAt = 3L,
                action = "create",
                skillName = "周报整理",
                description = "整理零散记录为周报",
                systemPrompt = "你是周报助手",
                reason = "工作流可复用",
            ),
        )
        val out = assembler.assemble("s-suggest-native", nativeModel(), workspacePath = "")
        assertTrue(
            "不得出现无 content 的 system 消息：${out.map { it.role to it.content }}",
            out.none { it.role == "system" && it.content.isNullOrBlank() },
        )
        assertFalse(out.any { it.content?.contains("周报整理") == true })
    }

    @Test
    fun `skill suggestions never reach the provider payload in json text mode`() = runBlocking {
        push(
            "s-suggest-text",
            UserMessage("u1", 1L, "帮我整理周报"),
            SkillSuggestion(
                id = "sg2",
                createdAt = 2L,
                action = "create",
                skillName = "周报整理",
                description = "整理零散记录为周报",
                systemPrompt = "你是周报助手",
                reason = "工作流可复用",
            ),
        )
        val out = assembler.assemble("s-suggest-text", jsonTextModel(), workspacePath = "")
        assertTrue(
            "不得出现无 content 的 system 消息：${out.map { it.role to it.content }}",
            out.none { it.role == "system" && it.content.isNullOrBlank() },
        )
        assertFalse(out.any { it.content?.contains("周报整理") == true })
    }

    /**
     * 纵深防御回归：即使将来又有新的 UI-only 消息类型漏登记跳过谓词，
     * sanitizeApiTranscript 也必须把无 content 的 system 消息挡在请求体之外。
     */
    @Test
    fun `sanitizeApiTranscript drops content-less system messages`() {
        val withHole = listOf(
            ApiMessage(role = "system", content = "正常系统提示"),
            ApiMessage(role = "user", content = "问题"),
            ApiMessage(role = "system", content = null),
            ApiMessage(role = "system", content = "   "),
        )
        val sanitized = sanitizeApiTranscript(withHole)
        assertEquals(2, sanitized.size)
        assertTrue(sanitized.none { it.role == "system" && it.content.isNullOrBlank() })
    }

    // ---------- 压缩摘要 ----------

    @Test
    fun `existing compaction summary is injected after the main system prompt`() = runBlocking {
        push("s-compact", UserMessage("u0", 1L, "早期历史，会被折叠进摘要"))
        val context = compactionManager.project("s-compact")
        compactionManager.compact("s-compact", context, keepFromIndex = 1)

        val expectedSummary = compactionManager.project("s-compact").summary!!
        val out = assembler.assemble("s-compact", nativeModel(), "")

        assertTrue(out.size >= 2)
        assertEquals(expectedSummary, out[1].content)
        assertFalse(out.any { it.role != "system" && it.content == expectedSummary })
    }

    @Test
    fun `persisted summary does not hide retained turns that still fit the model window`() = runBlocking {
        val sessionId = "s-retained"
        repeat(6) { index ->
            push(sessionId, UserMessage("u$index", index.toLong(), "retained request $index"))
        }
        val context = compactionManager.project(sessionId)
        compactionManager.compact(sessionId, context, keepFromIndex = 1)

        val out = assembler.assemble(sessionId, nativeModel(tokens = 200_000), "")
        val providerUsers = out.filter { it.role == "user" }.map { it.content }

        assertEquals((1..5).map { "retained request $it" }, providerUsers)
    }

    @Test
    fun `pure chat skips even persisted history system prompts`() = runBlocking {
        push("s-pure2", UserMessage("u1", 1L, "你好"))
        val out = assembler.assemble("s-pure2", nativeModel().copy(pureChatMode = true), "/ws")
        assertTrue(out.none { it.role == "system" })
        assertEquals("你好", out.single().content)
    }

    @Test
    fun `mcp rawToolName is preserved in native assistant tool calls`() = runBlocking {
        val mcpCall = ToolCall(
            id = "call-mcp",
            createdAt = 2L,
            tool = HarnessTool.MCP,
            args = buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("Kotlin coroutines")) },
            rawToolName = "mcp__mcp_websearch__search",
        )
        val mcpResult = ToolResult(
            id = "res-mcp",
            createdAt = 3L,
            toolCallId = "call-mcp",
            success = true,
            output = "搜索结果: Kotlin Coroutines 指南",
        )
        push("s-mcp", UserMessage("u1", 1L, "搜索一下"), mcpCall, mcpResult)
        val out = assembler.assemble("s-mcp", nativeModel(), "")

        val assistantMsg = out.first { it.role == "assistant" }
        val apiCall = assistantMsg.tool_calls?.single()
        assertEquals("call-mcp", apiCall?.id)
        assertEquals("mcp__mcp_websearch__search", apiCall?.function?.name)
    }
}
