package top.wkbin.taixu.harness.projection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import top.wkbin.taixu.harness.session.SessionTreeStore

/**
 * [HarnessUiBuffer] 集成测试：真实 [SessionMessageProjector] 作上游，
 * 验证「最新值 + 时间窗口」节流语义在真实数据流上的行为。
 *
 * 覆盖点：
 *  1. 时间窗口内的多次上游发射只放行最后一帧（UI 不会被每个 chunk 拖着重组）；
 *  2. 窗口过去后新内容仍能放行（不是"只发一次就死"）；
 *  3. 上游结束/被取消时兜底放行最后帧，UI 不会停在旧内容上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessUiBufferTest {

    private lateinit var database: AppDatabase
    private lateinit var projector: SessionMessageProjector
    private lateinit var buffer: HarnessUiBuffer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessionId = "session-under-test"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = SessionTreeStore(
            repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
            json = Json { ignoreUnknownKeys = true },
            logger = AppLogger(context, SensitiveDataRedactor { it }),
        )
        val tracker = CurrentSessionTracker()
        projector = SessionMessageProjector(store, tracker)
        buffer = HarnessUiBuffer(projector)
        // 让该会话成为前台聚焦会话，投影才会镜像到 foregroundMessages
        tracker.setCurrent(sessionId)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `只在窗口时刻放行最新帧而不是逐帧下发`() = runBlocking {
        buffer.startThrottling(throttleWindowMs = 200L, scope = scope)

        // 窗口内快速推入 5 帧（模拟 SSE chunk 高频到达）
        repeat(5) { index ->
            projector.streamText(sessionId, "m1", createdAt = 1L, text = "chunk-$index")
            delay(10)
        }
        // 等到窗口冲刷一次
        delay(400)

        val snapshot = buffer.throttledForegroundMessages.value
        val text = (snapshot.lastOrNull() as? AssistantText)?.text
        // 放行的必须是"最后一帧"的内容，而不是中间某一帧
        assertEquals("chunk-4", text)
    }

    @Test
    fun `窗口结束后新内容仍能继续放行`() = runBlocking {
        buffer.startThrottling(throttleWindowMs = 150L, scope = scope)

        projector.streamText(sessionId, "m1", createdAt = 1L, text = "first")
        delay(400)
        assertEquals("first", (buffer.throttledForegroundMessages.value.lastOrNull() as? AssistantText)?.text)

        projector.streamText(sessionId, "m1", createdAt = 1L, text = "second")
        delay(400)
        assertEquals("second", (buffer.throttledForegroundMessages.value.lastOrNull() as? AssistantText)?.text)
    }

    @Test
    fun `节流协程取消时兜底放行最后帧`() = runBlocking {
        val job = buffer.startThrottling(throttleWindowMs = 5_000L, scope = scope)

        projector.streamText(sessionId, "m1", createdAt = 1L, text = "tail")
        delay(150)
        // 窗口远未到达 → 此时不应已经放行
        assertTrue(
            "窗口未到时不应下发任何帧",
            buffer.throttledForegroundMessages.value.isEmpty(),
        )

        job.cancel()
        delay(200)
        assertEquals("tail", (buffer.throttledForegroundMessages.value.lastOrNull() as? AssistantText)?.text)
    }
}
