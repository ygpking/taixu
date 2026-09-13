package top.wkbin.taixu.runtime

import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for the environment visible inside Debian. */
@Singleton
class EnvironmentResolver @Inject constructor() {

    /**
     * 用户在 in-app 设置里配的「沙箱内置代理」（`http://host:port` / `socks5://host:port`）。
     * 由 SandboxProxySync 从 SettingsDataStore.sandboxHttpProxy 持续同步；非空时 baseEnvironment 注入代理。默认 null 不注入。
     */
    @Volatile var overrideProxy: String? = null

    fun runtimePath(): String = listOf(
        "/root/.local/bin",
        "/opt/taixu/bin",
        "/usr/local/sbin",
        "/usr/local/bin",
        "/usr/sbin",
        "/usr/bin",
        "/sbin",
        "/bin",
    ).joinToString(":")

    fun baseEnvironment(interactive: Boolean): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        put("PATH", runtimePath())
        // 沙箱内置代理（overrideProxy 非空时注入，让沙箱内 git/curl 走代理）
        overrideProxy?.trim()?.takeIf { it.isNotEmpty() }?.let { proxyUrl ->
            put("http_proxy", proxyUrl)
            put("https_proxy", proxyUrl)
            put("HTTP_PROXY", proxyUrl)
            put("HTTPS_PROXY", proxyUrl)
        }
        // HostBridge — 沙箱通过 localhost HTTP 桥接触发宿主侧操作（APK 安装、Shell 执行）
        put("TAIXU_BRIDGE_URL", "http://127.0.0.1:7980")
        put("TAIXU_BRIDGE_PORT", "7980")
        // Android 二进制执行参考路径（不加入主 PATH，避免与 Debian 工具冲突）
        // 使用 taixu-android-exec 包装器或 taixu-host shell 来执行 Android 命令
        put("ANDROID_BIN_PATH", "/system/bin:/system/xbin")
        put("ANDROID_LIB_PATH", "/system/lib64:/system/lib:/vendor/lib64:/vendor/lib")
        if (interactive) {
            put("TERM", "xterm-256color")
        } else {
            put("TERM", "dumb")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("CI", "true")
            put("NONINTERACTIVE", "1")
        }
    }

    fun merge(
        manifest: Map<String, String> = emptyMap(),
        provider: Map<String, String> = emptyMap(),
        interactive: Boolean = false,
    ): Map<String, String> = buildMap {
        putAll(baseEnvironment(interactive))
        putAll(manifest)
        putAll(provider)
    }
}
