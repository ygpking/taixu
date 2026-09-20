package top.wkbin.taixu.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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
import top.wkbin.taixu.harness.UserMessage

/**
 * `SessionTreeStore.laneLocks` 泄漏回归测试。
 *
 * 缺陷：`laneLocks` 的 key 形如 `"$sessionId/$laneName"`，`deleteSession` 原先只删库数据、
 * 不删锁 —— 每删一个会话就永久留下它的 Mutex（无人再引用，却被 map 强引用），
 * 会话 id 是 UUID 不复用，于是纯泄漏且随会话数单调增长。
 * 同届的十余个 session 维度容器（SessionMessageProjector/CheckpointStore/…）都有清理，
 * 唯独这一处漏了。
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
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage(id = "m1", createdAt = 1L, text = "hi"))
        assertEquals("append/ensure 应创建至少一把 lane 锁", 1, store.laneLockCountForTest)

        store.deleteSession(sessionId)
        assertEquals("删除会话后 lane 锁必须被回收", 0, store.laneLockCountForTest)
    }

    @Test
    fun `lane locks of sibling sessions are not affected`() = runBlocking {
        store.ensureMainLane("a")
        store.ensureMainLane("b")
        store.append("a", UserMessage(id = "a1", createdAt = 1L, text = "x"))
        store.append("b", UserMessage(id = "b1", createdAt = 2L, text = "y"))
        assertEquals(2, store.laneLockCountForTest)

        store.deleteSession("a")
        assertEquals("只应清掉目标会话的锁", 1, store.laneLockCountForTest)

        store.deleteSession("b")
        assertEquals(0, store.laneLockCountForTest)
    }

    /**
     * 关键边界：会话 id 互为前缀时不能误删（"a" 不能把 "ab" 的锁带走）。
     * 这正是"用分隔符拼 key + 带分隔符做前缀匹配"要挡住的情况。
     */
    @Test
    fun `prefix-looking session ids do not delete each other's locks`() = runBlocking {
        store.ensureMainLane("user")
        store.ensureMainLane("user-2")
        assertEquals(2, store.laneLockCountForTest)

        store.deleteSession("user")
        assertEquals(1, store.laneLockCountForTest)
        assertTrue(
            "user-2 的锁仍在（否则前缀匹配漏了分隔符）",
            store.laneLockCountForTest == 1,
        )
    }
}
