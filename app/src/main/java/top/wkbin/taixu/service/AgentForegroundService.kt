package top.wkbin.taixu.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import top.wkbin.taixu.R
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.harness.HarnessLoop
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent 后台执行前台服务：
 * 当任意一个或多个 Agent 正在运行时启动保活服务。
 * 为每个并行运行的 Agent 会话分发专属的系统通知（标题含会话名与当前执行动作），
 * 支持独立点击【停止】以及执行完毕后的【回复】续跑。
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    /**
     * 两个重依赖都必须走 dagger.Lazy。
     *
     * Hilt 注入 `Lazy<T>` 只给一个包装器，不在 Service 创建期构造 T；而 [HarnessLoop]
     * 是多依赖的 @Singleton 重图（Room / DataStore / ProviderClient / ToolExecutor /
     * MCP / 子智能体等 —— 见 [top.wkbin.taixu.TaiXuApplication] 里对同一问题的注释）。
     * 若是 eager 注入，这张图就得在**主线程**上构造完成才能进入 onCreate：Hilt 生成类
     * 必须先把 @Inject 字段填好，用户代码的 onCreate 才可能安全访问它们，而这一切都发生在
     * 服务能上报前台态之前。主线程被构造占用多久，上报就被推迟多久；系统留给
     * startForegroundService() 的窗口很短，一旦超期即抛 ForegroundServiceDidNotStartInTimeException
     * 并杀掉进程 —— 崩溃点落在 ActivityThread 的消息循环里，本类一行日志都留不下。
     * 这与现场吻合：三个版本、三个日期、三次同一异常，而本类没有任何日志输出。
     */
    @Inject lateinit var harnessLoopLazy: Lazy<HarnessLoop>
    @Inject lateinit var sessionDaoLazy: Lazy<HarnessSessionRepository>

    /**
     * Room 会话仓储的惰性取用。首次调用时 [HarnessLoop] 通常已把整图构造完毕，
     * 此处近乎零成本；保留 getter 是为了让三处调用点（运行中刷新 / 完成通知 / 收集回调）
     * 不必各自关心构造时机。
     */
    private val sessionDao: HarnessSessionRepository get() = sessionDaoLazy.get()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 服务生命周期之外的清理作用域：用于「停止 Agent」这类必须跑完的收尾动作。
     * 独立于 [serviceScope]，服务销毁时不被取消 —— 与 RuntimeForegroundService 同口径。
     */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collecting = false
    private var processLock: PowerManager.WakeLock? = null
    /**
     * 以下四个容器在 Main 线程（[serviceScope] 的 collectLatest 回调）写入，
     * 却被 IO 线程（[startNotificationRefresh] 的刷新循环）读取。
     * 原先用 `mutableSetOf` / `mutableMapOf`：HashMap 在 add/put 触发扩容时，
     * 并发遍历的 `toList()` 可能读到 rehash 中间态 —— 轻则快照缺条目（通知漏更新/漏发完成通知），
     * 重则抛 ConcurrentModificationException 打死刷新协程（通知时长从此不再走字）。
     * 换成 ConcurrentHashMap 支撑的实现，与仓库内 SessionMessageProjector / SessionStateMirrors
     * 等同类容器保持同一口径。
     */
    private val activeNotifSessionIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 记录每个会话开始运行的时间戳，用于通知中显示已运行时长。 */
    private val sessionStartTimes: MutableMap<String, Long> = ConcurrentHashMap()
    /** 定时刷新通知的 Job，运行期间周期性更新一次，降低被系统判定为闲置服务的概率。 */
    private var notificationRefreshJob: Job? = null
    /** 唤醒锁续期 Job：长任务期间周期重置锁超时，避免中途掉落。 */
    private var lockRefreshJob: Job? = null
    /** 上次成功发布的常驻通知内容指纹，用于跳过内容未变化的重复 notify。 */
    private val lastNotifKeys: MutableMap<String, String> = ConcurrentHashMap()

    override fun onCreate() {
        super.onCreate()
        // 渠道必须先于 startForeground 建好：targetSdk 26+ 上若渠道不存在，
        // 前台通知会被系统直接丢弃（服务看似已进前台，通知栏却是空的）。
        // 渠道创建是本地 binder 调用（毫秒级），不构成超时风险。
        ensureNotificationChannel()
        // 前台态必须在这里就占住，不能等到 onStartCommand。
        // 系统给 startForegroundService() 留的上报窗口很短，期间任何一次主线程排队
        // （冷启动、Hilt 注入、内存压力下的 GC）都可能把上报推到窗口之外，
        // 超时即抛 ForegroundServiceDidNotStartInTimeException 杀进程。此处先用占位通知占位，
        // 真正的会话通知由 onStartCommand 的收集回调按实际状态覆盖。
        // 上报失败时立即停服收口：这种失败多半是通知渠道/图标之类的确定性错误，
        // 停服不能让系统撤回已经启动的计时器（该超时仍可能发生），
        // 但可以避免服务带着"从未进入前台"的幽灵状态继续空转，并把失败原因留在日志里。
        if (!safeStartForeground(
                PRIMARY_NOTIFICATION_ID,
                placeholderNotification(getString(R.string.taixu_agent_ready)),
            )
        ) {
            Log.w(TAG, "进入前台态失败，停止服务以避免状态不一致")
            stopSelf()
        }
    }

    private fun ensureNotificationChannel() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.taixu_agent_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "用于展示 AI 深度思考与任务进度的常驻通知"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
            // 清理历史版本遗留的灵动岛/胶囊渠道，避免系统设置里残留无效项
            runCatching { manager.deleteNotificationChannel(LEGACY_CAPSULE_CHANNEL_ID) }
        }.onFailure { Log.w(TAG, "创建通知渠道失败", it) }
    }

    /**
     * Android 14+（API 34）起前台服务需声明类型，且部分类型有运行时长上限，
     * 超时由系统回调本方法；若不在时限内退出前台，系统会抛
     * ForegroundServiceDidNotStopInTimeException 杀进程（与本类修的那个崩溃同源不同型）。
     *
     * 这里的选择：超时即主动退出前台并停服，把"该会话是否仍在跑"交给 HarnessLoop 自身
     * 的中断恢复机制去处理，避免整个进程被杀。不用重新 startForeground 来续命 ——
     * 系统对同一次启动的 startForeground 次数有限制，超限后会被拒绝。
     * 具体时长上限以系统实现为准，此处不做硬编码断言。
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "前台服务到达类型化时长上限，退出前台并停止服务 startId=$startId")
        stopForegroundSafely(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            ACTION_STOP -> {
                // 不可在主线程懒取 HarnessLoop：首次 get() 会构造整张重图。
                processScope.launch {
                    runCatching {
                        val loop = harnessLoopLazy.get()
                        if (!targetSessionId.isNullOrBlank()) loop.cancel(targetSessionId) else loop.cancel()
                    }.onFailure { Log.w(TAG, "取消 Agent 失败", it) }
                }
                // 若本服务并非由运行中的会话维持（collecting=false，例如仅由通知按钮把服务拉起来），
                // 后续没有状态流可以收口 —— 直接退出前台并停服，避免 onCreate 占住的前台态滞留成幽灵通知。
                if (!collecting) {
                    stopForegroundSafely(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            else -> {
                // 前台态已在 onCreate 占住，这里只补同步持锁 + 状态收集。
                acquireProcessLock()
                if (!collecting) {
                    collecting = true
                    // 关键：只把「取依赖」这一步挪到 IO，收集本身仍留在 Main（与改动前一致），
                    // 避免顺带改变通知/持锁等回调的线程语义。
                    // 首次 get() 会构造整张重图，放 Main 上正是本次崩溃的成因；
                    // withContext 是挂起切换，主线程在此期间不会被阻塞。
                    serviceScope.launch {
                        val harnessLoop = runCatching {
                            withContext(Dispatchers.IO) { harnessLoopLazy.get() }
                        }.getOrElse { error ->
                            // 构造失败就把标志复位：否则收集协程已死、标志却还是 true，
                            // 后续再来的 start 请求会以为有人在收集而直接跳过，服务永远收不到状态。
                            Log.w(TAG, "构造 HarnessLoop 失败，放弃本次状态收集", error)
                            collecting = false
                            return@launch
                        }
                        combine(
                            harnessLoop.sessionRunStates,
                            harnessLoop.sessionStatuses,
                        ) { runStates, statuses ->
                            runStates to statuses
                        }.collectLatest { (runStates, statuses) ->
                            val runningEntries = runStates.filter { it.value == SessionRunState.RUNNING }
                            if (runningEntries.isNotEmpty()) {
                                acquireProcessLock()
                                startProcessLockRefresh()
                                val now = System.currentTimeMillis()
                                runningEntries.forEach { (sessionId, _) ->
                                    activeNotifSessionIds.add(sessionId)
                                    sessionStartTimes.putIfAbsent(sessionId, now)
                                    val notifId = sessionNotificationId(sessionId)
                                    val sessionTitle = sessionDao.findById(sessionId)?.title ?: getString(R.string.taixu_agent_default_title)
                                    val statusText = statuses[sessionId]?.takeIf { it.isNotBlank() } ?: getString(R.string.taixu_agent_thinking)
                                    latestSessionStatuses[sessionId] = statusText
                                    val startTime = sessionStartTimes[sessionId] ?: now
                                    val elapsedSeconds = (now - startTime) / 1000L
                                    // 与刷新循环共用内容指纹：状态文本未变时不重复 notify，
                                    // 避免处理器的每个中间状态都触发一次通知栏重绘。
                                    val key = "$sessionTitle|$statusText|${elapsedSeconds / 60}"
                                    if (lastNotifKeys[sessionId] != key) {
                                        lastNotifKeys[sessionId] = key
                                        val notif = sessionNotification(sessionId, sessionTitle, statusText, elapsedSeconds)
                                        safeNotify(notifId, notif)
                                    }
                                }
                                startNotificationRefresh()
                            } else {
                                stopNotificationRefresh()
                                stopProcessLockRefresh()
                                val previouslyRunning = activeNotifSessionIds.toList()
                                // 通知 id 依赖 activeNotifSessionIds 的成员（primary 会话特判为
                                // PRIMARY_NOTIFICATION_ID）。必须在 clear() **之前**算好：
                                // 清空后再算，集合为空 → 每个会话都落回 PRIMARY_NOTIFICATION_ID，
                                // 同一轮内多个会话完成时后写覆盖先写 —— 除最后一条外全部丢失。
                                val completedNotifIds = previouslyRunning.associateWith { sessionNotificationId(it) }
                                activeNotifSessionIds.clear()
                                sessionStartTimes.clear()
                                previouslyRunning.forEach { sessionId ->
                                    // 等待审批不是完成：批准后立即续跑，不发完成通知弹窗，
                                    // 常驻通知保持最后一次状态（通常是"等待用户批准"）直到恢复运行。
                                    if (runStates[sessionId] == SessionRunState.WAITING_APPROVAL) return@forEach
                                    val notifId = completedNotifIds.getValue(sessionId)
                                    val sessionTitle = sessionDao.findById(sessionId)?.title ?: getString(R.string.taixu_agent_default_title)
                                    safeNotify(notifId, completedNotification(sessionId, sessionTitle))
                                }
                                releaseProcessLock()
                                stopForegroundSafely(STOP_FOREGROUND_DETACH)
                                stopSelf()
                            }
                        }
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopNotificationRefresh()
        stopProcessLockRefresh()
        releaseProcessLock()
        serviceScope.cancel()
        // processScope 不随服务销毁取消：ACTION_STOP 的取消动作与收尾必须在服务死后跑完，
        // 否则用户点了【停止】只是通知消失、Agent 仍在后台空转。
        super.onDestroy()
    }

    private val latestSessionStatuses: MutableMap<String, String> = ConcurrentHashMap()

    private fun startNotificationRefresh() {
        if (notificationRefreshJob?.isActive == true) return
        notificationRefreshJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MS.milliseconds)
                val snapshot = activeNotifSessionIds.toList()
                if (snapshot.isEmpty()) break
                val now = System.currentTimeMillis()
                snapshot.forEach { sessionId ->
                    val startTime = sessionStartTimes[sessionId] ?: continue
                    val elapsedSeconds = (now - startTime) / 1000L
                    val notifId = sessionNotificationId(sessionId)
                    val sessionTitle = runCatching { sessionDao.findById(sessionId)?.title }
                        .getOrNull() ?: getString(R.string.taixu_agent_default_title)
                    val currentStatus = latestSessionStatuses[sessionId] ?: getString(R.string.taixu_agent_thinking)
                    // 内容指纹：标题 + 状态 + 分钟级时长。秒级数字抖动不触发重发，
                    // 避免"看起来在跑"的通知每几十秒无意义地重绘一次系统通知栏。
                    val key = "$sessionTitle|$currentStatus|${elapsedSeconds / 60}"
                    if (lastNotifKeys[sessionId] == key) return@forEach
                    lastNotifKeys[sessionId] = key
                    val notif = sessionNotification(
                        sessionId = sessionId,
                        title = sessionTitle,
                        status = currentStatus,
                        elapsedSeconds = elapsedSeconds,
                    )
                    safeNotify(notifId, notif)
                }
            }
        }
    }

    private fun stopNotificationRefresh() {
        notificationRefreshJob?.cancel()
        notificationRefreshJob = null
        latestSessionStatuses.clear()
        lastNotifKeys.clear()
    }

    private fun acquireProcessLock() {
        if (processLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(PowerManager::class.java)
            processLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .also { it.acquire(LOCK_TIMEOUT_MS) }
            Log.i(TAG, "Acquired partial wake lock for agent execution")
        }.onFailure { Log.w(TAG, "获取进程锁失败", it) }
    }

    /**
     * 长任务续期：Hold 期间周期性重新计时，避免超过 [LOCK_TIMEOUT_MS] 后锁自动掉落，
     * 导致长时间运行的 Agent 在息屏中途被 CPU 休眠冻结。
     */
    private fun startProcessLockRefresh() {
        if (lockRefreshJob?.isActive == true) return
        lockRefreshJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(LOCK_REFRESH_INTERVAL_MS)
                // 先释放再获取以重置超时计时；释放/获取之间有极短空窗，可忽略。
                releaseProcessLock()
                acquireProcessLock()
            }
        }
    }

    private fun stopProcessLockRefresh() {
        lockRefreshJob?.cancel()
        lockRefreshJob = null
    }

    private fun releaseProcessLock() {
        val lock = processLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "释放进程锁失败", it) }
        processLock = null
    }

    private fun sessionNotificationId(sessionId: String): Int =
        AgentNotificationIds.forSession(sessionId, activeNotifSessionIds.firstOrNull())

    private fun placeholderNotification(status: String): Notification {
        val stopPending = PendingIntent.getService(
            this,
            PRIMARY_NOTIFICATION_ID,
            Intent(this, AgentForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return runningNotification(
            title = getString(R.string.taixu_agent_default_title),
            contentText = status,
            stopPendingIntent = stopPending,
        )
    }

    private fun sessionNotification(
        sessionId: String,
        title: String,
        status: String,
        elapsedSeconds: Long = 0L,
    ): Notification {
        val stopPending = PendingIntent.getService(
            this,
            sessionNotificationId(sessionId),
            Intent(this, AgentForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = if (elapsedSeconds > 0) {
            "${formatElapsed(elapsedSeconds)} · $status"
        } else {
            status
        }
        return runningNotification(
            title = "太墟 · $title",
            contentText = contentText,
            stopPendingIntent = stopPending,
        )
    }

    private fun runningNotification(
        title: String,
        contentText: String,
        stopPendingIntent: PendingIntent,
    ): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.taixu_notification)
        .setContentTitle(title)
        .setContentText(contentText)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setProgress(0, 0, true)
        .addAction(
            NotificationCompat.Action(
                R.drawable.taixu_notification,
                getString(R.string.taixu_notification_stop),
                stopPendingIntent,
            ),
        )
        .build()

    private fun completedNotification(sessionId: String, title: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(
            this,
            sessionNotificationId(sessionId),
            Intent(this, AgentReplyReceiver::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            flags,
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.taixu_notification_reply_to, title)).build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.taixu_notification,
            getString(R.string.taixu_notification_reply),
            replyPending,
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.taixu_notification)
            .setContentTitle(getString(R.string.taixu_agent_task_completed, title))
            .setContentText(getString(R.string.taixu_agent_next_task_hint))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.taixu_agent_next_task_hint)),
            )
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .build()
    }

    private fun formatElapsed(seconds: Long): String = when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3600}小时${(seconds % 3600) / 60}分"
    }

    /**
     * 前台态上报。返回是否成功 —— 调用方据此决定是否停服收口。
     *
     * 原先这里用 `runCatching` 静默吞掉异常：服务会带着"从未进入前台"的状态继续跑，
     * 通知栏却是空的，而且日志里没有任何线索 —— 本次崩溃排查时，
     * 现场恰恰缺少这条最关键的证据。改为返回布尔值把失败暴露给调用方。
     */
    private fun safeStartForeground(id: Int, notification: Notification): Boolean =
        runCatching { startForeground(id, notification) }
            .onFailure { Log.w(TAG, "startForeground 失败", it) }
            .isSuccess

    private fun stopForegroundSafely(flag: Int) {
        runCatching { stopForeground(flag) }
            .onFailure { Log.w(TAG, "stopForeground 失败", it) }
    }

    private fun safeNotify(id: Int, notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }.onFailure { Log.w(TAG, "发布通知失败", it) }
    }

    companion object {
        const val ACTION_START = "top.wkbin.taixu.action.AGENT_START"
        const val ACTION_STOP = "top.wkbin.taixu.action.AGENT_STOP"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val KEY_REPLY = "agent_reply"
        private const val CHANNEL_ID = "taixu-agent-v5"
        private const val LEGACY_CAPSULE_CHANNEL_ID = "taixu-agent-capsule-v4"
        private const val PRIMARY_NOTIFICATION_ID = AgentNotificationIds.PRIMARY
        private const val TAG = "AgentForegroundService"
        private const val WAKE_LOCK_TAG = "taixu:agent-execution"
        private const val LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        /** 锁续期间隔：略小于单次超时，保证超长任务（>4h）全程持锁不中断。 */
        private const val LOCK_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L
        /** 运行期间通知刷新间隔：15 秒。状态与运行时长展示本就到分钟级即可，
         *  配合内容指纹去重，可避免 2 秒一次的高频重绘持续唤醒 SystemUI。 */
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 15_000L

        fun start(context: Context, sessionId: String? = null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "通知权限未授权，跳过 Agent 前台保活服务")
                return
            }
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START)
            sessionId?.let { intent.putExtra(EXTRA_SESSION_ID, it) }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "启动 Agent 前台服务失败", it) }
        }

        fun startFromReply(context: Context, sessionId: String? = null) {
            start(context, sessionId)
        }
    }
}
