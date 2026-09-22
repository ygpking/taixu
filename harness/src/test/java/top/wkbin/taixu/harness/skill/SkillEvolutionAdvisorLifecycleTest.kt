package top.wkbin.taixu.harness.skill

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.RoomAgentContextRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.fixtures.FakeAdvisorModelClient
import top.wkbin.taixu.harness.fixtures.FakeBudgetPreferences
import top.wkbin.taixu.harness.projection.CurrentSessionTracker
import top.wkbin.taixu.harness.projection.SessionMessageProjector
import top.wkbin.taixu.harness.session.SessionTreeStore

/**
 * 技能进化顾问的**触发点**测试。
 *
 * 这三条在此前**结构上写不出来**：顾问原先直接依赖 `ProviderClient`（具体类，
 * 构造链要 OkHttpClient + ProviderRepository + AiModelRepository + McpManager），
 * 于是整条链路零覆盖——"一次失败分析烧掉 30 分钟冷却""LRU 驱逐后静默丢建议"
 * 这类缺陷因此长期存在。PR-A 抽了 `AdvisorModelClient` 窄接口，这里用它的 fake。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillEvolutionAdvisorLifecycleTest {

    private lateinit var database: AppDatabase
    private lateinit var projector: SessionMessageProjector
    private lateinit var store: SessionTreeStore
    private lateinit var advisor: SkillEvolutionAdvisor
    private lateinit var client: FakeAdvisorModelClient

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        store = SessionTreeStore(runtimeRepo, json, logger)
        projector = SessionMessageProjector(store, CurrentSessionTracker())
        client = FakeAdvisorModelClient()
        advisor = SkillEvolutionAdvisor(
            providerClient = client,
            skillRepository = AgentSkillRepository(database.agentSkillDao()),
            settingsDataStore = top.wkbin.taixu.core.datastore.AgentPreferences(
                top.wkbin.taixu.core.datastore.SettingsDataStore(context, top.wkbin.taixu.core.security.SecretManager()),
            ).apply { },

            sessionDao = top.wkbin.taixu.core.database.RoomHarnessSessionRepository(database.harnessSessionDao()),
            projector = projector,
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val model = ModelConfig(
        name = "n", provider = "p", model = "gpt-x",
        baseUrl = "https://example.com", apiKey = "k",
    )

    /** 造一段"做了足够多工具调用"的会话，让门槛放行。 */
    private suspend fun seedWork(sessionId: String) {
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage("u1", 1L, "帮我把网络层重构成 Ktor"))
        repeat(4) { i ->
            store.append(
                sessionId,
                ToolCall("t$i", 10L + i, top.wkbin.taixu.harness.HarnessTool.READ, kotlinx.serialization.json.JsonObject(emptyMap())),
            )
            store.append(
                sessionId,
                ToolResult("r$i", 20L + i, "t$i", true, "output $i"),
            )
        }
        store.append(sessionId, AssistantText("a1", 50L, "重构完成"))
    }

    @Test
    fun `digest is read from the durable history so LRU eviction cannot silence it`() = runBlocking {
        seedWork("s-lru")
        // 关键：只往 store 里写，**不碰实时投影**——模拟会话已被 LRU 驱逐、
        // live flow 拿不到东西的情形。旧实现读 messagesFlow().value 会拿到空 → 静默返回。
        projector.removeSession("s-lru")

        client.setModel(model)
        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = """{"action":"create","name":"网络层重构 Checklist","system_prompt":"先列依赖再动手","reason":"可复用"}""",
            toolCalls = emptyList(),
        )

        advisor.analyzeAndEmit("s-lru")

        assertEquals("必须真的发起一次分析（digest 来自持久源，不是内存快照）", 1, client.chatCalls)
        val projected = projector.messagesFlow("s-lru").value.filterIsInstance<SkillSuggestion>()
        assertEquals("建议应落入会话", 1, projected.size)
    }

    @Test
    fun `a failed analysis does not consume the cooldown window`() = runBlocking {
        seedWork("s-cool")
        client.setModel(model)
        // 第一次：LLM 返回不可解析内容（这是最高频结局，提示词明示 none 是默认答案）
        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = "我觉得这次没什么可沉淀的",
            toolCalls = emptyList(),
        )

        advisor.analyzeAndEmit("s-cool")
        assertEquals("失败结局也发起了分析", 1, client.chatCalls)

        // 第二次：同样的会话立刻给出可用提案——若冷却在失败时就被写，
        // 这里会被 30 分钟窗口挡掉，用户 30 分钟内再也看不到建议。
        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = """{"action":"create","name":"网络层重构 Checklist","system_prompt":"先列依赖再动手","reason":"可复用"}""",
            toolCalls = emptyList(),
        )
        advisor.analyzeAndEmit("s-cool")

        assertEquals("失败不得占用冷却窗口", 2, client.chatCalls)
        val projected = projector.messagesFlow("s-cool").value.filterIsInstance<SkillSuggestion>()
        assertEquals("第二次应成功产出建议", 1, projected.size)
    }

    @Test
    fun `a successful suggestion arms the cooldown`() = runBlocking {
        seedWork("s-arm")
        client.setModel(model)
        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = """{"action":"create","name":"网络层重构 Checklist","system_prompt":"先列依赖再动手","reason":"可复用"}""",
            toolCalls = emptyList(),
        )

        advisor.analyzeAndEmit("s-arm")
        advisor.analyzeAndEmit("s-arm") // 紧接着再来一次

        assertEquals("成功产出后应进入冷却，不得重复分析", 1, client.chatCalls)
    }

    @Test
    fun `forgetSession clears the cooldown state`() = runBlocking {
        seedWork("s-forget")
        client.setModel(model)
        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = """{"action":"create","name":"网络层重构 Checklist","system_prompt":"先列依赖再动手","reason":"可复用"}""",
            toolCalls = emptyList(),
        )
        advisor.analyzeAndEmit("s-forget")
        assertEquals(1, client.chatCalls)

        // 模拟 HarnessLoop.deleteSession 的回收
        advisor.forgetSession("s-forget")

        client.chatResult = top.wkbin.taixu.harness.ChatResult(
            content = """{"action":"create","name":"另一个建议","system_prompt":"x","reason":"y"}""",
            toolCalls = emptyList(),
        )
        advisor.analyzeAndEmit("s-forget")
        assertEquals("forget 后冷却状态应被回收", 2, client.chatCalls)
    }

    @Test
    fun `pending skill suggestions survive the compaction boundary`() = runBlocking {
        val sessionId = "s-compact"
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage("u1", 1L, "第一个问题"))
        store.append(sessionId, AssistantText("a1", 2L, "第一个答复"))
        store.append(
            sessionId,
            SkillSuggestion(
                id = "sg1",
                createdAt = 3L,
                action = "create",
                skillName = "待处理建议",
                description = "d",
                systemPrompt = "p",
                reason = "r",
            ),
        )
        store.append(sessionId, UserMessage("u2", 4L, "第二个问题"))
        store.append(sessionId, AssistantText("a2", 5L, "第二个答复"))

        val compaction = top.wkbin.taixu.harness.compaction.CompactionManager(
            RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
            json,
        )
        val context = compaction.project(sessionId)
        // keepFrom=2：把 sg1 切进压缩区
        compaction.compact(sessionId, context, keepFromIndex = 2)

        val after = compaction.project(sessionId)
        assertTrue(
            "未处理的技能建议不得被压缩折出投影",
            after.messages.any { it is SkillSuggestion && it.id == "sg1" },
        )
    }

    @Test
    fun `recordSuggestionStatus appends a same id message with the new status`() = runBlocking {
        val sessionId = "s-status"
        store.ensureMainLane(sessionId)
        val suggestion = SkillSuggestion(
            id = "sg-status",
            createdAt = 1L,
            action = "create",
            skillName = "待处理建议",
            description = "d",
            systemPrompt = "p",
            reason = "r",
        )
        store.append(sessionId, suggestion)

        advisor.recordSuggestionStatus(sessionId, suggestion, "dismissed")

        // 断言读持久源：实时投影的首建是异步合历史的，刚 append 完立刻读 value 可能只看到一半。
        val same = projector.loadHistory(sessionId).filter { it.id == "sg-status" }
        assertEquals("同 id 应有两条（原 + 处置）", 2, same.size)
        val last = same.last() as SkillSuggestion
        assertEquals("dismissed", last.status)
        assertEquals("内容不得被改写", suggestion.skillName, last.skillName)
    }

    @Test
    fun `maybeSuggest returns a joinable handle`() = runBlocking {
        val sessionId = "s-job"
        store.ensureMainLane(sessionId)
        // 契约：调用方能拿到句柄并 join 它（deleteSession 就是靠这个取消 in-flight 分析）。
        // 这里没配模型，分析会快速失败并被内部 catch 吞掉 → job 正常完成；
        // 对一个"已完成"的 job 调 cancelAndJoin 必须是无害的（生产里删会话时它可能早已结束）。
        val job = advisor.maybeSuggest(sessionId)
        withContext(Dispatchers.Default) { job.cancelAndJoin() }
        assertTrue("join 一个已结束的 job 不得抛错", job.isCompleted)
        // 再 join 一次仍然无害（deleteSession 与正常结束可能竞争同一条 job）
        withContext(Dispatchers.Default) { job.cancelAndJoin() }
        assertTrue(job.isCompleted)
    }
}
