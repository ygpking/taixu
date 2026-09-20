package top.wkbin.taixu.harness.session

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
import top.wkbin.taixu.harness.UserMessage

/**
 * `SessionTreeStore.laneLocks` 泄漏回归测试。
 *
 * 缺陷：`laneLocks` 的 key 形如 `"$sessionId/$laneName"`，`deleteSession` 原先只删库数据、
 * 不删锁 —— 每删一个会话就永久留下它的 Mutex（无人再引用，却被 map 强引用）。
 * 会话 id 是 UUID 不复用，于是纯泄漏且随会话数单调增长。
 * 同届的十余个 session 维度容器（SessionMessageProjector/CheckpointStore/…）都有清理，
 * 唯独这一处漏了。
 *
 * 注意：只有真正走 [SessionTreeStore.append]/[SessionTreeStore.rewindBefore] 等
 * "持锁操作"才会创建 lane 锁；[SessionTreeStore.ensureMainLane] 只建 lane 行，不建锁。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTreeStoreLaneLockTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SessionTreeStore(
            repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
            json = Json { ignoreUnknownKeys = true },
            logger = AppLogger(context, SensitiveDataRedactor { it }),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `deleting a session releases its lane locks`() = runBlocking {
        val sessionId = "session-lane-lock"
        store.append(sessionId, UserMessage(id = "m1", createdAt = 1L, text = "hi"))
        assertEquals("append 应创建 lane 锁", 1, store.laneLockCountForTest)

        store.deleteSession(sessionId)
        assertEquals("删除会话后 lane 锁必须被回收", 0, store.laneLockCountForTest)
    }

    @Test
    fun `lane locks of sibling sessions are not affected`() = runBlocking {
        store.append("a", UserMessage(id = "a1", createdAt = 1L, text = "x"))
        store.append("b", UserMessage(id = "b1", createdAt = 2L, text = "y"))
        assertEquals(2, store.laneLockCountForTest)

        store.deleteSession("a")
        assertEquals("只应清掉目标会话的锁", 1, store.laneLockCountForTest)

        store.deleteSession("b")
        assertEquals(0, store.laneLockCountForTest)
    }

    /**
     * 关键边界：会话 id 互为前缀时不能误删（"user" 不能把 "user-2" 的锁带走）。
     * 这正是「用分隔符拼 key + 带分隔符做前缀匹配」要挡住的情况 ——
     * 若清理写成 `startsWith(sessionId)`（漏掉分隔符），本用例会失败。
     */
    @Test
    fun `prefix-looking session ids do not delete each other's locks`() = runBlocking {
        store.append("user", UserMessage(id = "u1", createdAt = 1L, text = "x"))
        store.append("user-2", UserMessage(id = "u2", createdAt = 2L, text = "y"))
        assertEquals(2, store.laneLockCountForTest)

        store.deleteSession("user")

        assertEquals("只应删掉 'user' 的锁，'user-2' 必须留下", 1, store.laneLockCountForTest)
        // 反向确认留下的是 user-2：再删它应当清空
        store.deleteSession("user-2")
        assertEquals(0, store.laneLockCountForTest)
    }
}
