package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话删除时上下文数据清理的回归测试。
 *
 * 缺陷：`agent_plans` / `agent_scratchpads` / `scope='session'` 的 `agent_memories`
 * 三张表都以 sessionId 为键，但**没有任何调用方**在删会话时清它们 ——
 * `clearScratchpads` / `deletePlanBySession` 只被 UI 手动按钮与工具自清理动作调用过。
 * 会话 id 是 UUID 且不复用，于是每删一个会话就永久留下它的计划、草稿与 session 记忆：
 * 纯孤儿数据（不会被任何会话再读到），且会污染 `countMemories` 的配额统计口径
 * （`MAX_MEMORIES_PER_OWNER = 100`）。
 *
 * 关键边界：`global` / `project` 记忆的 owner **不是** sessionId，语义就是跨会话，
 * **不得**被单次会话删除带走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentContextSessionCleanupTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AgentContextDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentContextDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun memory(id: String, scope: String, ownerId: String, key: String) = AgentMemoryEntity(
        id = id,
        scope = scope,
        ownerId = ownerId,
        kind = "fact",
        key = key,
        value = "v-$id",
    )

    private suspend fun allMemories() = dao.observeAllMemories().first()

    @Test
    fun `session delete removes plan scratchpads and session memories`() = runBlocking {
        val sid = "sess-1"
        dao.savePlan(AgentPlanEntity(sessionId = sid, goal = "目标", stepsJson = "[]", status = "active"))
        dao.saveScratchpad(AgentScratchpadEntity(sessionId = sid, key = "draft", value = "草稿"))
        dao.saveMemory(memory("m1", "session", sid, "k1"))

        dao.deleteSessionContextData(sid)

        assertNull("计划应被清掉", dao.getPlanBySession(sid))
        assertEquals("草稿应被清掉", 0, dao.listScratchpads(sid).size)
        assertEquals("session 记忆应被清掉", 0, allMemories().size)
    }

    /**
     * 核心边界：global / project 记忆的 owner 不是会话 id，语义是跨会话 —— 必须留下。
     * 若把清理写成"按 ownerId 删"而不限定 `scope='session'`，这两类会被误删。
     */
    @Test
    fun `global and project memories survive a session delete`() = runBlocking {
        val sid = "sess-2"
        val project = "/workspace/demo"
        dao.saveMemory(memory("g1", "global", "", "global-rule"))
        dao.saveMemory(memory("p1", "project", project, "project-fact"))
        dao.saveMemory(memory("s1", "session", sid, "session-note"))

        dao.deleteSessionContextData(sid)

        val remaining = allMemories().associateBy { it.id }
        assertEquals("只应删掉 session 那条", 2, remaining.size)
        assertNotNull("global 记忆必须保留", remaining["g1"])
        assertNotNull("project 记忆必须保留", remaining["p1"])
        assertNull("session 记忆应被删", remaining["s1"])
    }

    @Test
    fun `other sessions data is untouched`() = runBlocking {
        dao.savePlan(AgentPlanEntity(sessionId = "keep", goal = "保留", stepsJson = "[]", status = "active"))
        dao.saveScratchpad(AgentScratchpadEntity(sessionId = "keep", key = "k", value = "v"))
        dao.saveMemory(memory("keep-m", "session", "keep", "k"))
        dao.savePlan(AgentPlanEntity(sessionId = "drop", goal = "删除", stepsJson = "[]", status = "active"))
        dao.saveScratchpad(AgentScratchpadEntity(sessionId = "drop", key = "k", value = "v"))
        dao.saveMemory(memory("drop-m", "session", "drop", "k"))

        dao.deleteSessionContextData("drop")

        assertNotNull("另一会话的计划不受影响", dao.getPlanBySession("keep"))
        assertEquals(1, dao.listScratchpads("keep").size)
        assertEquals(1, allMemories().size)
        assertEquals("keep-m", allMemories().single().id)
    }

    /** 同一 project owner 在别的会话里创建的记忆，不得因某会话删除而消失。 */
    @Test
    fun `project memory shared by another session survives`() = runBlocking {
        val project = "/workspace/shared"
        dao.saveMemory(memory("p-a", "project", project, "a"))
        dao.saveMemory(memory("p-b", "project", project, "b"))

        dao.deleteSessionContextData("some-session")

        assertEquals("project 记忆与 sessionId 无关，不应被删", 2, allMemories().size)
    }

    @Test
    fun `cleanup is idempotent`() = runBlocking {
        val sid = "sess-3"
        dao.savePlan(AgentPlanEntity(sessionId = sid, goal = "g", stepsJson = "[]", status = "active"))
        dao.deleteSessionContextData(sid)
        dao.deleteSessionContextData(sid)
        assertNull(dao.getPlanBySession(sid))
    }
}
