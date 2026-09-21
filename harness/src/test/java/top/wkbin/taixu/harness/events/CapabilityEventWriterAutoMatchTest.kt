package top.wkbin.taixu.harness.events

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.database.AgentSkillEntity
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.projection.LiveMessagePort

/**
 * 自动匹配技能事件的可见性测试（丙）。
 *
 * 治本方案把"技能加载"从模型自觉改为系统自动注入，随之而来一个新风险：
 * **系统替模型做了决定，却不留痕迹**——用户与开发者都无从判断"这轮到底用了没"。
 * 本测试锁定：自动匹配命中时必须在会话内写入幂等的 [CapabilityEvent]，
 * 且与 @提及 事件不重复、与系统提示的注入口径一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityEventWriterAutoMatchTest {

    private class RecordingPort : LiveMessagePort {
        val appended = mutableListOf<Pair<String, HarnessMessage>>()
        val snapshots = mutableMapOf<String, MutableList<HarnessMessage>>()

        override suspend fun append(sessionId: String, message: HarnessMessage) {
            appended += sessionId to message
            snapshots.getOrPut(sessionId) { mutableListOf() }.add(message)
        }

        override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
            append(sessionId, message)
        }

        override fun snapshot(sessionId: String): List<HarnessMessage> =
            snapshots[sessionId]?.toList().orEmpty()
    }

    private lateinit var database: AppDatabase
    private lateinit var port: RecordingPort
    private lateinit var writer: CapabilityEventWriter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        port = RecordingPort()
        writer = CapabilityEventWriter(
            port = port,
            skillRepository = top.wkbin.taixu.core.database.AgentSkillRepository(database.agentSkillDao()),
            mcpServerRepository = top.wkbin.taixu.core.database.McpServerRepository(
                database.mcpServerDao(),
                SecretManager(),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedSkill(id: String, name: String, description: String, enabled: Boolean = true) {
        database.agentSkillDao().upsert(
            AgentSkillEntity(
                id = id,
                name = name,
                description = description,
                systemPrompt = "提示",
                triggerCommand = "/${id}",
                iconName = "Code",
                isEnabled = enabled,
                isBuiltin = false,
                isImmutable = false,
                category = "版本控制",
                resourcePath = null,
            ),
        )
    }

    private val model = ModelConfig(
        name = "m",
        provider = "p",
        model = "gpt-x",
        baseUrl = "https://example.com",
        apiKey = "k",
    )

    @Test
    fun `auto matched skill writes a visible event`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断")

        writer.writeIfMentioned(
            "s1", "um1", emptySet(), model,
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
        )

        val events = port.appended.map { it.second }.filterIsInstance<CapabilityEvent>()
        assertEquals(1, events.size)
        assertEquals(CapabilityEvent.Kind.SKILL, events[0].kind)
        assertEquals("auto:um1:git_workflow", events[0].id)
        assertTrue("事件应说明是系统自动匹配：${events[0].details}", events[0].details.contains("自动匹配"))
    }

    @Test
    fun `auto match event is idempotent`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断")
        val text = "帮我处理 git 分支合并冲突诊断"

        writer.writeIfMentioned("s1", "um1", emptySet(), model, latestUserMessage = text)
        writer.writeIfMentioned("s1", "um1", emptySet(), model, latestUserMessage = text)

        assertEquals(1, port.appended.size)
    }

    @Test
    fun `mentioned skill is not duplicated as auto match`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断")

        writer.writeIfMentioned(
            "s1", "um1", setOf("git_workflow"), model,
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
        )

        val events = port.appended.map { it.second }.filterIsInstance<CapabilityEvent>()
        assertEquals("同一技能不得同时记为 @提及 与自动匹配", 1, events.size)
        assertEquals("skill:um1:git_workflow", events[0].id)
    }

    @Test
    fun `disabled skill produces no auto event`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断", enabled = false)

        writer.writeIfMentioned(
            "s1", "um1", emptySet(), model,
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
        )

        assertTrue("禁用技能不得产生自动匹配事件", port.appended.isEmpty())
    }

    @Test
    fun `blank message produces no auto event`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断")
        writer.writeIfMentioned("s1", "um1", emptySet(), model)
        assertTrue(port.appended.isEmpty())
    }

    /**
     * 口径一致性防线：丙（事件卡片）必须与甲（提示注入）严格同步。
     * 甲在 `ToolCallMode.DISABLED` 时不注入技能正文；若丙仍在此时写卡片，
     * 就会出现「卡片说系统自动匹配了、提示里其实什么都没有」的两侧漂移。
     */
    @Test
    fun `disabled tool mode produces no auto event`() = runBlocking {
        seedSkill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断")

        writer.writeIfMentioned(
            "s1", "um1", emptySet(), model.copy(toolCallMode = ToolCallMode.DISABLED),
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
        )

        assertTrue("禁用工具时甲不注入正文，丙也不应写卡片：${port.appended}", port.appended.isEmpty())
    }
}
