package top.wkbin.taixu.runtime.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HostBridge` Bearer token 校验的行为与健壮性测试。
 *
 * 安全背景：`HostBridge` 的 `/api/shell` 能**在宿主侧以 Shizuku/Root 权限执行任意命令**，
 * 桥接只监听 127.0.0.1，而 Android 上 loopback 不按 UID 隔离（任意应用可连），
 * 因此这个 token 是唯一的访问控制点。
 */
class HostBridgeAuthTest {

    private val key = "9f2c4a1b7e8d0356af1c9b2e4d7f8a03"

    @Test
    fun `accepts the exact token`() {
        assertTrue(HostBridgeAuth.matches(key, key))
    }

    @Test
    fun `rejects null and blank candidates`() {
        assertFalse("缺 Authorization 头时不得放行", HostBridgeAuth.matches(null, key))
        assertFalse("空 token 不得放行", HostBridgeAuth.matches("", key))
    }

    @Test
    fun `rejects when no token is configured`() {
        // 不允许「未配置 token 就放行」的降级路径
        assertFalse(HostBridgeAuth.matches("anything", null))
        assertFalse(HostBridgeAuth.matches("anything", ""))
        assertFalse(HostBridgeAuth.matches("", ""))
    }

    @Test
    fun `rejects near-miss tokens`() {
        assertFalse(HostBridgeAuth.matches(key.dropLast(1), key))
        assertFalse(HostBridgeAuth.matches(key + "x", key))
        assertFalse(HostBridgeAuth.matches(key.uppercase(), key))
        // 前 31 位全对、只错最后一位 —— 时序侧信道的攻击目标正是这种输入
        assertFalse(HostBridgeAuth.matches(key.dropLast(1) + "Z", key))
    }

    @Test
    fun `rejects a token that differs only in length`() {
        // 摘要后比较长度恒定，长度差异不构成额外旁路
        assertFalse(HostBridgeAuth.matches(key + key, key))
    }
}
