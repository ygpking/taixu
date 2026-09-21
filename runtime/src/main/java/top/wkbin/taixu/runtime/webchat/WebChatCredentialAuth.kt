package top.wkbin.taixu.runtime.webchat

import java.security.MessageDigest

/**
 * Web 协作台请求的凭据提取与校验（纯逻辑，不依赖 Android HTTP 栈）。
 *
 * **为什么单独抽出来**：配对码是宿主级凭据 —— 持有它可以读写工作区文件、驱动 agent。
 * 原实现只从 `?token=` 查询参数取值，而 SSE 用 `EventSource` 建立
 * （**无法自定义请求头**），于是配对码必然出现在 URL 里，进而落进访问日志、
 * 浏览器历史与 Referer。
 *
 * 抽成纯函数后可被单测直接覆盖真实逻辑（不再是"测试里另写一份同构实现"——
 * 那种影子实现会在生产代码改坏时依然通过）。
 *
 * 支持三种来源，任一匹配即通过：
 * 1. `Authorization: Bearer <pin>` 请求头（**首选**，不落日志）；
 * 2. `wc_session` Cookie（供 `EventSource` 使用）；
 * 3. `?token=` 查询参数（**仅为向后兼容保留**；前端已迁移到 Cookie）。
 */
internal object WebChatCredentialAuth {

    /** 会话 Cookie 名，必须与前端 `webchat/src/api.ts` 的 `SESSION_COOKIE` 一致。 */
    const val SESSION_COOKIE = "wc_session"

    /**
     * @param pin 当前配对码
     * @param authorization `Authorization` 头原值（可为 null）
     * @param cookieHeader `Cookie` 头原值（可为 null）
     * @param queryToken `?token=` 的值（可为 null）
     */
    fun isAuthenticated(
        pin: String,
        authorization: String?,
        cookieHeader: String?,
        queryToken: String?,
    ): Boolean {
        if (pin.isBlank()) return false
        return candidates(authorization, cookieHeader, queryToken).any { matches(it, pin) }
    }

    /**
     * 常时比较候选凭据与配对码，与 `bridge.HostBridgeAuth` 同一口径。
     *
     * `String.equals` 在首个不等字符处即返回，比较耗时可测；配合无退避的枚举，
     * 攻击者能把「正确前缀长度」当作反馈信号逐位收敛。先按 UTF-8 求 SHA-256 再
     * 比较摘要，长度恒定（32 字节），既不泄露长度也不泄露前缀匹配进度。
     *
     * 注意：常时比较只消除**时序**信道，不降低配对码空间的重要性 ——
     * 猜测成本由 `WebChatAuthThrottle` 的失败退避负责，两者必须同时在场。
     */
    fun matches(candidate: String?, expected: String?): Boolean {
        if (candidate.isNullOrEmpty() || expected.isNullOrEmpty()) return false
        return MessageDigest.isEqual(sha256(candidate), sha256(expected))
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    /** 按优先级列出各来源非空的凭据值，供校验与测试断言。 */
    internal fun candidates(
        authorization: String?,
        cookieHeader: String?,
        queryToken: String?,
    ): List<String> = listOfNotNull(
        bearerOf(authorization),
        sessionCookieOf(cookieHeader),
        queryToken?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** 从 `Authorization` 头剥出 Bearer 值。前缀大小写敏感（避免歧义），两侧空白容忍。 */
    internal fun bearerOf(authorization: String?): String? =
        authorization
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 从 `Cookie` 头里取 [SESSION_COOKIE]；可与其他 Cookie 共存。 */
    internal fun sessionCookieOf(cookieHeader: String?): String? =
        cookieHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 认证成功后要下发的 `Set-Cookie` 值。
     *
     * `Path=/webchat` 限定作用域；**不带 `Secure` 是刻意的** —— 本服务只监听明文
     * HTTP（回环/局域网），加 `Secure` 会让浏览器直接拒收，反而把前端逼回 query 传参。
     */
    fun sessionCookieValue(pin: String): String =
        "$SESSION_COOKIE=$pin; Path=/webchat; SameSite=Strict; Max-Age=86400"
}
