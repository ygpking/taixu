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
        // 被放弃段不足 MIN_MESSAGES 时不生成摘要；markSummarized 在校验前登记，
        // 因此同一 leaf 不会反复重算——这条用例把该行为钉住。
        store.ensureMainLane("s-short")
        store.append("s-short", UserMessage("u1", 1, "问题"))
        store.append("s-short", AssistantText("a1", 2, "答复"))
        store.append("s-short", UserMessage("u2", 3, "换个问法"))
        store.rewindBefore("s-short", "u2")
        store.append("s-short", UserMessage("u2b", 4, "另一种问法"))

        val summarized = branchSummarizer.summarizeAbandonedBranch("s-short", "a1", "u2b")
        assertFalse("被放弃段太短不得生成摘要", summarized)
    }
}
