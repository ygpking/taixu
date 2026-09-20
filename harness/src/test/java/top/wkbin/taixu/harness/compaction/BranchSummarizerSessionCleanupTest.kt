package top.wkbin.taixu.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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

    private suspend fun seedSession(sessionId: String) {
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage("u1", 1, "任务：重构网络层"))
        store.append(sessionId, AssistantText("a1", 2, "好的，我先看现状"))
        store.append(sessionId, UserMessage("u2", 3, "方案A：使用 Retrofit"))
        store.append(sessionId, AssistantText("a2", 4, "方案A完成了一半，遇到循环依赖"))
        store.append(sessionId, UserMessage("u3", 5, "继续方案A"))
        store.append(sessionId, AssistantText("a3", 6, "仍然受阻"))
        store.rewindBefore(sessionId, "u2")
        store.append(sessionId, UserMessage("u2b", 7, "方案B：使用 Ktor"))
        store.append(sessionId, AssistantText("a2b", 8, "方案B可行"))
    }

    @Test
    fun `dropSession releases the per-session dedupe registry`() = runBlocking {
        seedSession("s-leak")
        branchSummarizer.summarizeAbandonedBranch("s-leak", "a3", "a2b")
        assertEquals("摘要成功后应留下去重登记", 1, branchSummarizer.summarizedSessionCountForTest())

        branchSummarizer.dropSession("s-leak")
        assertEquals("会话删除后去重登记必须被回收", 0, branchSummarizer.summarizedSessionCountForTest())
    }

    @Test
    fun `dropping one session keeps other sessions' registry`() = runBlocking {
        seedSession("s-keep")
        seedSession("s-drop")
        branchSummarizer.summarizeAbandonedBranch("s-keep", "a3", "a2b")
        branchSummarizer.summarizeAbandonedBranch("s-drop", "a3", "a2b")
        assertEquals(2, branchSummarizer.summarizedSessionCountForTest())

        branchSummarizer.dropSession("s-drop")
        assertEquals(1, branchSummarizer.summarizedSessionCountForTest())
    }
}
