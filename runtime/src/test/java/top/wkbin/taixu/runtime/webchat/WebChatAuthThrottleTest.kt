package top.wkbin.taixu.runtime.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对码失败退避的测试（**直接覆盖生产代码** [WebChatAuthThrottle]）。
 *
 * 安全背景：服务监听 `0.0.0.0`（局域网可达）、配对码仅 6 位数字、
 * 响应带 `Access-Control-Allow-Origin: *`。没有退避时，同局域网任意设备
 * （乃至用户浏览器里的任意恶意页面）都能无成本枚举这个宿主级凭据。
 *
 * 时间用可推进的假时钟注入，不依赖真实等待。
 */
class WebChatAuthThrottleTest {

    private var clock = 0L
    private val throttle = WebChatAuthThrottle(now = { clock })

    @Test
    fun `fewer than threshold failures do not lock`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE - 1) {
            throttle.recordFailure("10.0.0.2")
        }
        assertFalse("未达阈值不得锁定", throttle.isLocked("10.0.0.2"))
        assertEquals(0L, throttle.retryAfterSeconds("10.0.0.2"))
    }

    @Test
    fun `reaching threshold locks with base backoff`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) {
            throttle.recordFailure("10.0.0.2")
        }
        assertTrue("达到阈值必须锁定", throttle.isLocked("10.0.0.2"))
        assertEquals(
            WebChatAuthThrottle.DEFAULT_LOCKOUT_BASE_MS / 1000,
            throttle.retryAfterSeconds("10.0.0.2"),
        )
    }

    @Test
    fun `further failures escalate the lockout and stay capped`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) { throttle.recordFailure("10.0.0.2") }
        throttle.recordFailure("10.0.0.2")
        clock += WebChatAuthThrottle.DEFAULT_LOCKOUT_BASE_MS
        assertTrue("锁定期内继续失败应仍在锁定期", throttle.isLocked("10.0.0.2"))
        assertEquals(
            WebChatAuthThrottle.DEFAULT_LOCKOUT_BASE_MS / 1000,
            throttle.retryAfterSeconds("10.0.0.2"),
        )
        // 连续失败足够多次后，锁定时长必须收敛到上限而不是无限翻倍（防 shl 溢出）
        repeat(40) { throttle.recordFailure("10.0.0.2") }
        clock += WebChatAuthThrottle.DEFAULT_LOCKOUT_MAX_MS - 1
        assertTrue("上限内的最长锁定仍有效", throttle.isLocked("10.0.0.2"))
        assertTrue(
            "锁定时长不得超过上限",
            throttle.retryAfterSeconds("10.0.0.2") <= WebChatAuthThrottle.DEFAULT_LOCKOUT_MAX_MS / 1000 + 1,
        )
    }

    @Test
    fun `lockout expires after the backoff window`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) { throttle.recordFailure("10.0.0.2") }
        clock += WebChatAuthThrottle.DEFAULT_LOCKOUT_BASE_MS
        assertFalse("退避窗口过后必须放行", throttle.isLocked("10.0.0.2"))
    }

    @Test
    fun `success clears the failure streak`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE - 1) { throttle.recordFailure("10.0.0.2") }
        throttle.recordSuccess("10.0.0.2")
        // 成功后计数归零：再失败一次不该直接触发锁定
        throttle.recordFailure("10.0.0.2")
        assertFalse("成功后的单次失败不得锁定", throttle.isLocked("10.0.0.2"))
    }

    @Test
    fun `other sources are unaffected by one source lockout`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) { throttle.recordFailure("10.0.0.2") }
        assertTrue(throttle.isLocked("10.0.0.2"))
        assertFalse("锁定期按来源隔离", throttle.isLocked("10.0.0.3"))
    }

    @Test
    fun `global backstop locks every source on distributed guessing`() {
        // 每个来源都失败一次、轮番换来源：单来源永远达不到阈值，
        // 只能靠全局兜底拦住"换 IP 也无成本"的分布式枚举。
        var source = 0
        repeat(WebChatAuthThrottle.DEFAULT_GLOBAL_FAILURE_LIMIT) {
            throttle.recordFailure("10.0.0.${source++ % 250 + 1}")
        }
        assertTrue("全局失败数超限必须全体锁定", throttle.isLocked("10.0.0.99"))
        assertTrue("全局锁定对从未失败过的来源同样生效", throttle.isLocked("brand-new-source"))
    }

    @Test
    fun `unknown source is normalized instead of bypassing the throttle`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) {
            throttle.recordFailure(null)
            throttle.recordFailure("")
            throttle.recordFailure("   ")
        }
        // null / 空串 / 空白都归一到同一把钥匙：攻击者不能靠"不带头信息"绕过计数
        assertTrue(throttle.isLocked(null))
        assertTrue(throttle.isLocked(""))
    }

    @Test
    fun `reset drops all backoff state`() {
        repeat(WebChatAuthThrottle.DEFAULT_MAX_FAILURES_PER_SOURCE) { throttle.recordFailure("10.0.0.2") }
        throttle.reset()
        assertFalse("reset 后不得残留锁定", throttle.isLocked("10.0.0.2"))
    }
}
