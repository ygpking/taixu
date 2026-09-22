package top.wkbin.taixu.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.session.SessionTreeStore

/**
 * `BranchSummarizer.summarizedFromLeafs` 泄漏回归测试。
 *
 * 缺陷：去重登记以 sessionId 为键、leafId 集合为值，会话 id 是 UUID 不复用；
 * HarnessLoop.deleteSession 清理了 SessionTreeStore / CheckpointStore / SessionMessageProjector
 * 等容器，唯独没碰这里 → 每删一个会话就永久留下一份登记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BranchSummarizerSessionCleanupTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var store: SessionTreeStore
    private lateinit var branchSummarizer: BranchSummarizer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val json = Json { ignoreUnknownKeys = true }
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        store = SessionTreeStore(repository, json, logger)
        branchSummarizer = BranchSummarizer(repository, json, logger)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * 消息 id 必须按会话区分：`harness_entries.id` 上有唯一索引，而
     * [top.wkbin.taixu.core.database.HarnessRuntimeDao.insertEntry] 是 IGNORE ——
     * 两个会话复用同一批 id 时，第二个会话的 seed 会被静默丢弃，它的分支查出来是空的。
     * 生产环境 id 是 UUID，不会撞；只有测试夹具会。
     */
    private suspend fun seedSession(sessionId: String) {
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage("$sessionId-u1", 1, "任务：重构网络层"))
        store.append(sessionId, AssistantText("$sessionId-a1", 2, "好的，我先看现状"))
        store.append(sessionId, UserMessage("$sessionId-u2", 3, "方案A：使用 Retrofit"))
        store.append(sessionId, AssistantText("$sessionId-a2", 4, "方案A完成了一半，遇到循环依赖"))
        store.append(sessionId, UserMessage("$sessionId-u3", 5, "继续方案A"))
        store.append(sessionId, AssistantText("$sessionId-a3", 6, "仍然受阻"))
        store.rewindBefore(sessionId, "$sessionId-u2")
        store.append(sessionId, UserMessage("$sessionId-u2b", 7, "方案B：使用 Ktor"))
        store.append(sessionId, AssistantText("$sessionId-a2b", 8, "方案B可行"))
    }

    @Test
    fun `dropSession releases the per-session dedupe registry`() = runBlocking {
        seedSession("s-leak")
        // 必须真的生成摘要（被放弃段 ≥ MIN_MESSAGES）才谈得上"留下去重登记"：
        // 只断言登记数而不断言返回值的话，把摘要逻辑整段删掉测试依然会绿。
        val summarized = branchSummarizer.summarizeAbandonedBranch("s-leak", "s-leak-a3", "s-leak-a2b")
        assertTrue("被放弃分支应成功生成摘要（否则本测试无判别力）", summarized)
        assertEquals("摘要成功后应留下去重登记", 1, branchSummarizer.summarizedSessionCountForTest())

        branchSummarizer.dropSession("s-leak")
        assertEquals("会话删除后去重登记必须被回收", 0, branchSummarizer.summarizedSessionCountForTest())
    }

    @Test
    fun `dropping one session keeps other sessions' registry`() = runBlocking {
        seedSession("s-keep")
        seedSession("s-drop")
        assertTrue(branchSummarizer.summarizeAbandonedBranch("s-keep", "s-keep-a3", "s-keep-a2b"))
        assertTrue(branchSummarizer.summarizeAbandonedBranch("s-drop", "s-drop-a3", "s-drop-a2b"))
        assertEquals(2, branchSummarizer.summarizedSessionCountForTest())

        branchSummarizer.dropSession("s-drop")
        assertEquals(1, branchSummarizer.summarizedSessionCountForTest())
    }

    @Test
    fun `too short abandoned branch is skipped and not registered`() = runBlocking {
        // 被放弃段不足 MIN_MESSAGES 时不生成摘要；登记必须发生在长度校验**之后**，
        // 否则「先短后长」的分支（放弃时太短、后来又长起来再被放弃）会因为登记已存在
        // 而永远拿不到摘要——关键结论静默丢失。
        store.ensureMainLane("s-short")
        store.append("s-short", UserMessage("u1", 1, "问题"))
        store.append("s-short", AssistantText("a1", 2, "答复"))
        store.append("s-short", UserMessage("u2", 3, "换个问法"))
        store.rewindBefore("s-short", "u2")
        store.append("s-short", UserMessage("u2b", 4, "另一种问法"))

        val summarized = branchSummarizer.summarizeAbandonedBranch("s-short", "a1", "u2b")
        assertFalse("被放弃段太短不得生成摘要", summarized)
        assertEquals(
            "校验不通过时不得留下登记（否则该分支以后长起来也永不摘要）",
            0,
            branchSummarizer.summarizedSessionCountForTest(),
        )
    }

    /**
     * 回归（P3）：同一个被放弃 leaf，第一次因为"新叶子分叉点靠后"导致被放弃段太短而跳过；
     * 第二次换成"分叉点更早"的新叶子，被放弃段变长就必须能补上摘要。
     *
     * 旧代码先登记再校验：第一次的登记会把同一 oldLeafId 的第二次尝试永久挡掉。
     * （`abandonedMessages` 的长度只取决于 (oldLeaf 路径, newLeaf 路径) 的公共前缀长度，
     *  所以换 newLeaf 就能改变它——这是该缺陷唯一可达的触发路径。）
     */
    @Test
    fun `same old leaf still summarizes when the new leaf diverges earlier`() = runBlocking {
        store.ensureMainLane("s-grow")
        store.append("s-grow", UserMessage("u1", 1, "任务：重构网络层"))
        store.append("s-grow", AssistantText("a1", 2, "先看现状"))
        store.append("s-grow", UserMessage("u2", 3, "按方案 A 推进"))
        store.append("s-grow", AssistantText("a3", 4, "遇到循环依赖"))

        // 分支 X：从 a3 继续 u4/a4
        store.moveTo("s-grow", "a3")
        store.append("s-grow", UserMessage("u4", 5, "继续方案 A"))
        store.append("s-grow", AssistantText("a4", 6, "仍受阻"))
        // 分支 Y：从 u2 分叉（比 a3 更晚），u5/a5
        store.moveTo("s-grow", "u2")
        store.append("s-grow", UserMessage("u5", 7, "换方案 B"))
        store.append("s-grow", AssistantText("a5", 8, "方案 B 可行"))

        // 第一次：oldLeaf=a4、newLeaf=a5 → 公共前缀到 u2，被放弃段 = a3,u4,a4（3 条 < MIN）
        assertFalse(
            "被放弃段太短不得生成摘要",
            branchSummarizer.summarizeAbandonedBranch("s-grow", "a4", "a5"),
        )
        assertEquals("校验不通过时不得留下登记", 0, branchSummarizer.summarizedSessionCountForTest())

        // 分支 W：从 u1 就分叉（更早），u6
        store.moveTo("s-grow", "u1")
        store.append("s-grow", UserMessage("u6", 9, "完全另一个方向"))

        // 第二次：同一个 oldLeaf=a4，但 newLeaf=u6 分叉更早 → 被放弃段 = a1,u2,a3,u4,a4（5 条）
        assertTrue(
            "换更早分叉的新叶子后必须补上摘要（登记不能挡住这条路）",
            branchSummarizer.summarizeAbandonedBranch("s-grow", "a4", "u6"),
        )
    }
}
