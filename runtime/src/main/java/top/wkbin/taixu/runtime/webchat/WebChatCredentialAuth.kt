package top.wkbin.taixu.runtime.webchat

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
        return candidates(authorization, cookieHeader, queryToken).any { it == pin }
    }

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
