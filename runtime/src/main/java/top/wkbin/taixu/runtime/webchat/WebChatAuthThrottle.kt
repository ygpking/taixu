package top.wkbin.taixu.runtime.webchat

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Web 协作台配对码的**失败退避**（纯逻辑，不依赖 Android HTTP 栈，可被单测直接覆盖）。
 *
 * ## 为什么必须有
 * 配对码是宿主级凭据：持有它可以读写工作区文件、驱动 agent。而服务监听
 * `0.0.0.0`（局域网可达，见 `WebChatBridgeServer.start`）、配对码只有 6 位数字
 * （90 万空间）、响应又带 `Access-Control-Allow-Origin: *` ——
 * 三者叠加意味着**同局域网任意设备、乃至用户浏览器里任意恶意页面**都能无成本枚举配对码。
 *
 * 光把比对改成常时（[WebChatCredentialAuth]）只消除时序侧信道，**不提高猜测成本**：
 * 攻击者仍可用「是否 200/401」做反馈信号逐个收敛。真正的对策是让失败有代价：
 *
 *  · 单来源（按远端 IP）连续失败 [DEFAULT_MAX_FAILURES_PER_SOURCE] 次后进入指数退避锁定期，
 *    锁定时长随连续失败次数翻倍（30s → 60s → … → 上限 [DEFAULT_LOCKOUT_MAX_MS]）；
 *  · 全局兜底：短窗口内所有来源的失败总数超过 [DEFAULT_GLOBAL_FAILURE_LIMIT] 时，
 *    全体锁定 [DEFAULT_GLOBAL_LOCK_MS]——防「换来源也无成本」的分布式慢速枚举；
 *  · 认证成功即清空该来源的连续失败计数（正常用户一次成功后，不被自己此前的误触连累）。
 *
 * 退避只延长「下一次可尝试时间」，不永久封禁：忘记配对码时重启服务即可重置
 * （PIN 与退避状态都只活在进程内存里，不落盘）。
 */
internal class WebChatAuthThrottle(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxFailuresPerSource: Int = DEFAULT_MAX_FAILURES_PER_SOURCE,
    private val lockoutBaseMs: Long = DEFAULT_LOCKOUT_BASE_MS,
    private val lockoutMaxMs: Long = DEFAULT_LOCKOUT_MAX_MS,
    private val globalFailureLimit: Int = DEFAULT_GLOBAL_FAILURE_LIMIT,
    private val globalWindowMs: Long = DEFAULT_GLOBAL_WINDOW_MS,
    private val globalLockMs: Long = DEFAULT_GLOBAL_LOCK_MS,
) {
    private class Record(var failures: Int = 0, var lockedUntil: Long = 0L)

    private val records = ConcurrentHashMap<String, Record>()

    /** 全局失败时间戳队列（短窗口内计数），与 [globalLockedUntil] 共用一把锁保护。 */
    private val globalLock = Any()
    private val recentGlobalFailures = ArrayDeque<Long>()
    private var globalLockedUntil: Long = 0L

    /** 该来源当前是否处于锁定期（全局锁定期同样生效）。 */
    fun isLocked(sourceKey: String?): Boolean {
        val at = now()
        synchronized(globalLock) {
            if (globalLockedUntil > at) return true
        }
        val record = records[normalizeKey(sourceKey)] ?: return false
        return synchronized(record) { record.lockedUntil > at }
    }

    /** 距离解锁还剩多少秒（未锁定时为 0），供 `Retry-After` 响应头使用。 */
    fun retryAfterSeconds(sourceKey: String?): Long {
        val at = now()
        val until = maxOf(
            synchronized(globalLock) { globalLockedUntil },
            records[normalizeKey(sourceKey)]?.let { synchronized(it) { it.lockedUntil } } ?: 0L,
        )
        return if (until <= at) 0 else (until - at + 999) / 1000
    }

    /** 登记一次认证失败：累计到阈值即对该来源加锁，同时计入全局窗口。 */
    fun recordFailure(sourceKey: String?) {
        val at = now()
        // computeIfAbsent 原子建槽：getOrPut 的 get→null→put 在并发首败时会各建一条记录，
        // 丢掉一半计数——而"丢计数"在这里等于"少锁一次"。
        val record = records.computeIfAbsent(normalizeKey(sourceKey)) { Record() }
        synchronized(record) {
            record.failures += 1
            if (record.failures >= maxFailuresPerSource) {
                val steps = (record.failures - maxFailuresPerSource).coerceAtMost(MAX_BACKOFF_STEPS)
                record.lockedUntil = at + (lockoutBaseMs shl steps).coerceAtMost(lockoutMaxMs)
            }
        }
        registerGlobalFailure(at)
    }

    /** 认证成功：清空该来源的连续失败计数与锁定状态。 */
    fun recordSuccess(sourceKey: String?) {
        records.remove(normalizeKey(sourceKey))
    }

    /** 服务停止时调用，丢弃全部退避状态（与进程生命周期一致）。 */
    fun reset() {
        records.clear()
        synchronized(globalLock) {
            recentGlobalFailures.clear()
            globalLockedUntil = 0L
        }
    }

    private fun registerGlobalFailure(at: Long) {
        synchronized(globalLock) {
            while (recentGlobalFailures.isNotEmpty() && recentGlobalFailures.first < at - globalWindowMs) {
                recentGlobalFailures.removeFirst()
            }
            recentGlobalFailures.addLast(at)
            // `<=` 而不是 `<`：锁定期已过（或从未武装过）时必须允许重新武装。
            // 用 `<` 时，"锁定期恰好在这一刻到期"会让这次失败不计入新的全局锁，
            // 攻击者卡着解锁瞬间继续枚举就零成本。
            if (recentGlobalFailures.size >= globalFailureLimit && globalLockedUntil <= at) {
                globalLockedUntil = at + globalLockMs
            }
        }
    }

    private fun normalizeKey(sourceKey: String?): String =
        sourceKey?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_SOURCE

    companion object {
        const val UNKNOWN_SOURCE = "unknown"

        /** 单来源连续失败达到该次数后开始锁定期。 */
        const val DEFAULT_MAX_FAILURES_PER_SOURCE = 5

        /** 首次锁定时长；之后每再失败一次翻倍。 */
        const val DEFAULT_LOCKOUT_BASE_MS = 30_000L

        /** 单来源锁定时长上限（15 分钟）。 */
        const val DEFAULT_LOCKOUT_MAX_MS = 15 * 60_000L

        /** 退避翻倍的最大步数，防 `shl` 溢出成负数。 */
        private const val MAX_BACKOFF_STEPS = 8

        /** 全局兜底：窗口内所有来源合计失败次数上限。 */
        const val DEFAULT_GLOBAL_FAILURE_LIMIT = 60

        /** 全局兜底的统计窗口。 */
        const val DEFAULT_GLOBAL_WINDOW_MS = 60_000L

        /** 触发全局兜底后的全体锁定时长。 */
        const val DEFAULT_GLOBAL_LOCK_MS = 5 * 60_000L
    }
}
