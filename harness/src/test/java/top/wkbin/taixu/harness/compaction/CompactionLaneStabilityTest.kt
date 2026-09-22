package top.wkbin.taixu.harness.compaction

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
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.session.SessionTreeStore

/**
 * 压缩落库前的 lane 稳定性复核（第二波回归，P1）。
 *
 * 背景：压缩的主干是一次数秒的 LLM 调用，期间任何并发写 lane 都会让叶子前进。
 * `appendToLane` 对陈旧 parentId 是**静默 rebase**（改写 parentId 挂到最新叶子），
 * 而 project() 用「retained 快照 + sequence 晚于压缩条目」重建上下文——窗口内写入的消息
 * 既不在 retained 也不满足 sequence 条件，从此永久退出投影：无异常、无日志、UI 里还在。
 * 切换压缩跑在 viewModelScope，不进 sessionJobs、不持 lane 锁，`isSessionBusy` 看不见它，
 * 与用户随手发消息的窗口天然重叠。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactionLaneStabilityTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var store: SessionTreeStore
    private lateinit var compaction: CompactionManager

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
        compaction = CompactionManager(repository, json)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `message written during the compaction window stays in the projection`() = runBlocking {
        store.ensureMainLane("s-race")
        store.append("s-race", UserMessage("u1", 1L, "任务：重构网络层"))
        store.append("s-race", AssistantText("a1", 2L, "先看现状"))
        store.append("s-race", UserMessage("u2", 3L, "按方案 A 推进"))
        store.append("s-race", AssistantText("a2", 4L, "遇到循环依赖"))
        store.append("s-race", UserMessage("u3", 5L, "继续"))
        store.append("s-race", AssistantText("a3", 6L, "仍受阻"))

        // 取快照（相当于压缩开始：retained 在此冻结）
        val snapshot = compaction.project("s-race")
        val before = snapshot.messages.map { it.id }.toSet()

        // 模拟压缩 LLM 调用期间用户发送的消息（生产路径 SessionTreeStore.append）
        store.append("s-race", UserMessage("u-inflight", 7L, "顺手问一句：登录 bug 修了吗"))

        // 压缩落库：此处应因 lane 叶子已前进而放弃，原样返回 context
        val result = compaction.compact("s-race", snapshot, keepFromIndex = 2)

        val after = compaction.project("s-race").messages.map { it.id }.toSet()
        assertTrue(
            "压缩窗口内写入的消息必须仍在投影里：缺 ${before.plus("u-inflight").minus(after)}",
            "u-inflight" in after,
        )
        assertTrue(
            "放弃本次压缩时不得新增压缩条目",
            result.messages.map { it.id }.toSet() == snapshot.messages.map { it.id }.toSet(),
        )

        // 快照里的老消息也一条都不少（不得被 retained 快照永久定格）
        assertTrue("原有消息不得从投影消失", before.all { it in after })
    }

    @Test
    fun `lane unchanged still compacts normally`() = runBlocking {
        store.ensureMainLane("s-ok")
        store.append("s-ok", UserMessage("u1", 1L, "任务：重构网络层"))
        store.append("s-ok", AssistantText("a1", 2L, "先看现状"))
        store.append("s-ok", UserMessage("u2", 3L, "按方案 A 推进"))
        store.append("s-ok", AssistantText("a2", 4L, "遇到循环依赖"))

        val snapshot = compaction.project("s-ok")
        compaction.compact("s-ok", snapshot, keepFromIndex = 2)

        val after = compaction.project("s-ok")
        assertTrue("无并发写入时应正常产出摘要", !after.summary.isNullOrBlank())
        assertEquals("折叠 2 条后保留 2 条", 2, after.messages.size)
    }
}
