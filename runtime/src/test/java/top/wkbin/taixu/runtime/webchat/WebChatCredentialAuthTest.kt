package top.wkbin.taixu.runtime.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 协作台凭据来源的测试（**直接覆盖生产代码** [WebChatCredentialAuth]）。
 *
 * 安全背景：配对码是宿主级凭据 —— 持有它可以读写工作区文件、驱动 agent。
 * 原实现只从 `?token=` 查询参数取值，而 SSE 用 `EventSource` 建立
 * （**无法自定义请求头**），于是配对码必然出现在 URL 里，进而落进访问日志、
 * 浏览器历史与 Referer。
 *
 * 注意：本测试刻意**不**在测试里另写一份解析逻辑（那是"影子实现"，
 * 生产代码改坏时依然会通过）。全部断言都打在真实实现上。
 */
class WebChatCredentialAuthTest {

    private val pin = "123456"

    private fun auth(
        authorization: String? = null,
        cookie: String? = null,
        query: String? = null,
        pin: String = this.pin,
    ) = WebChatCredentialAuth.isAuthenticated(pin, authorization, cookie, query)

    @Test
    fun `accepts bearer header, session cookie and legacy query token`() {
        assertTrue("Bearer 头", auth(authorization = "Bearer $pin"))
        assertTrue("wc_session Cookie", auth(cookie = "wc_session=$pin"))
        assertTrue("旧的 query 传参（向后兼容）", auth(query = pin))
    }

    @Test
    fun `rejects wrong credentials from every source`() {
        assertFalse(auth(authorization = "Bearer 000000"))
        assertFalse(auth(cookie = "wc_session=000000"))
        assertFalse(auth(query = "000000"))
        assertFalse("三者皆缺时不得放行", auth())
    }

    @Test
    fun `rejects blank pin even with matching blank credential`() {
        // 未初始化 pin 时不得出现"空对空"放行
        assertFalse(auth(pin = ""))
        assertFalse(auth(pin = "   ", query = "   "))
        assertFalse(WebChatCredentialAuth.isAuthenticated("", null, null, ""))
    }

    @Test
    fun `session cookie is found among other cookies`() {
        assertTrue(auth(cookie = "theme=dark; wc_session=$pin; lang=zh"))
        assertTrue(auth(cookie = "wc_session=$pin"))
        assertTrue("前后空白应被容忍", auth(cookie = "  wc_session=$pin  "))
    }

    @Test
    fun `unrelated cookies and blank values do not authenticate`() {
        assertFalse("同名后缀的 Cookie 不得误命中", auth(cookie = "not_wc_session=$pin"))
        assertFalse(auth(cookie = "wc_session="))
        assertFalse(auth(cookie = "wc_session=   "))
        assertFalse(auth(authorization = "Bearer "))
        assertFalse(auth(authorization = ""))
        assertFalse(auth(query = ""))
        assertFalse(auth(query = "   "))
    }

    @Test
    fun `any correct source passes even if another is stale`() {
        // 重新配对后：头里是新码、Cookie 里还是旧码 —— 必须通过
        assertTrue(auth(authorization = "Bearer $pin", cookie = "wc_session=old"))
        // 反之亦然
        assertTrue(auth(authorization = "Bearer wrong", cookie = "wc_session=$pin"))
        // 都错才拒绝
        assertFalse(auth(authorization = "Bearer wrong", cookie = "wc_session=old", query = "wrong"))
    }

    @Test
    fun `bearer prefix parsing tolerates extra spaces and is case sensitive`() {
        assertTrue(auth(authorization = "Bearer  $pin"))
        assertFalse("小写 bearer 不接受（避免歧义，保持现状并被测试固化）", auth(authorization = "bearer $pin"))
    }

    @Test
    fun `candidate order is header then cookie then query`() {
        assertEquals(
            listOf("h", "c", "q"),
            WebChatCredentialAuth.candidates("Bearer h", "wc_session=c", "q"),
        )
        assertEquals(listOf("q"), WebChatCredentialAuth.candidates(null, null, "q"))
        assertEquals(emptyList<String>(), WebChatCredentialAuth.candidates(null, "other=1", null))
    }

    @Test
    fun `set cookie value scopes path and omits Secure for plain http`() {
        val value = WebChatCredentialAuth.sessionCookieValue(pin)
        assertTrue(value.startsWith("${WebChatCredentialAuth.SESSION_COOKIE}=$pin"))
        assertTrue("限定作用域", value.contains("Path=/webchat"))
        assertTrue("限制跨站携带", value.contains("SameSite=Strict"))
        assertFalse("明文 HTTP 下加 Secure 会被浏览器拒收，反而逼回 query 传参", value.contains("Secure"))
    }

    @Test
    fun `cookie name is the single source of truth shared with the server`() {
        assertEquals("wc_session", WebChatCredentialAuth.SESSION_COOKIE)
        assertEquals(WebChatCredentialAuth.SESSION_COOKIE, WebChatBridgeServer.SESSION_COOKIE)
        assertNull(WebChatCredentialAuth.bearerOf(null))
    }

    @Test
    fun `matches is constant time in shape and rejects empty on both sides`() {
        // 常时比较走 sha256 + MessageDigest.isEqual：正确码必须命中，错误码必须拒绝，
        // 且任一侧为空都不得放行（不存在"空对空"降级路径）。
        assertTrue(WebChatCredentialAuth.matches(pin, pin))
        assertFalse(WebChatCredentialAuth.matches("000000", pin))
        assertFalse(WebChatCredentialAuth.matches(null, pin))
        assertFalse(WebChatCredentialAuth.matches(pin, null))
        assertFalse(WebChatCredentialAuth.matches("", ""))
        // 与 HostBridgeAuth 同一口径：不同长度的候选也不得因长度泄露而放行
        assertFalse(WebChatCredentialAuth.matches("1234567", pin))
        assertFalse(WebChatCredentialAuth.matches("12345", pin))
    }

    @Test
    fun `authentication goes through the constant time matcher`() {
        // isAuthenticated 必须复用 matches（而不是回到 String.equals 的早退比较）
        assertTrue(auth(authorization = "Bearer $pin"))
        assertFalse(auth(authorization = "Bearer 12345"))
    }
}
