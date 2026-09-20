package top.wkbin.taixu.runtime.bridge

import java.security.MessageDigest

/**
 * `HostBridge` 的 Bearer token 校验。
 *
 * **为什么单独抽出来**：`HostBridge` 的 `/api/shell` 可在**宿主（Android 系统）侧以
 * Shizuku/Root 权限执行任意 shell 命令**，桥接只监听 `127.0.0.1` 并以此为唯一凭据。
 * 而 Android 上 127.0.0.1 **并不按 UID 隔离** —— 同一设备上任意应用都能连到这个端口
 * （同一威胁模型已在 `harness` 的 `McpAuthFilter` 里写明并用常量时间比较处理）。
 *
 * 原实现是 `token == bridgeKey`：String 的 `equals` 在首个不等字符处提前返回，
 * 逐字节比较耗时可测，构成时序侧信道；配合「无失败退避 / 无尝试上限」，
 * 猜测方可以把「正确前缀长度」当作反馈信号逐字节收敛。
 *
 * 这里改用 [MessageDigest.isEqual]，与 `McpAuthFilter` 同一口径。
 * 注意：常量时间比较只消除**时序**信道，**不**降低 token 空间的重要性 ——
 * 凭据本身仍是 128 位随机（`UUID.randomUUID()` 去横线）。
 */
internal object HostBridgeAuth {

    /**
     * 常时比较 [candidate] 与 [expected]。
     *
     * `null` 或空值直接拒绝：不允许「未配置 token 就放行」的降级路径
     * （`McpAuthFilter` 里 `configuredToken.isNullOrEmpty() → false` 是同一原则）。
     *
     * 先按 UTF-8 求 SHA-256 再比较摘要，好处是：
     * 确保比较长度恒定（32 字节），不因候选串长度不同而泄露长度信息。
     */
    fun matches(candidate: String?, expected: String?): Boolean {
        if (candidate.isNullOrEmpty() || expected.isNullOrEmpty()) return false
        return MessageDigest.isEqual(sha256(candidate), sha256(expected))
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
