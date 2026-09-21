package top.wkbin.taixu.runtime.shell

import kotlinx.coroutines.flow.Flow

data class TerminalOutput(
    val stream: TerminalStream,
    val text: String,
)

enum class TerminalStream {
    STDOUT,
    STDERR,
    SYSTEM,
}

data class SessionConfig(
    val columns: Int = 80,
    val rows: Int = 24,
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val commandLine: String = "/bin/bash -i",
    val allowSttyResize: Boolean = commandLine == "/bin/bash -i" || commandLine == "/bin/sh -i",
    /** 终端会话进入时打印 TAIXU 横幅；MCP STDIO 等协议会话必须保持 false 以免污染输出流。 */
    val showBanner: Boolean = false,
    /**
     * 该会话存活期间是否视为"沙箱有任务"（前台保活服务据此持 CPU 唤醒锁）。
     * 交互式终端与工具会话保持 true——会话在，用户就在用；
     * MCP STDIO 这类长连接协议会话设为 false：它们绝大部分时间只是挂着等请求，
     * 真正的忙碌由 LinuxRuntime.withSandboxActivity 在请求-响应窗口内显式上报，
     * 否则常驻长连接会把唤醒锁永久钉死，息屏后 CPU 无法进入低功耗而持续耗电。
     */
    val countsAsSandboxBusy: Boolean = true,
)

interface LinuxSession {
    val pid: Long? get() = null
    val isAlive: Boolean
    val output: Flow<TerminalOutput>

    suspend fun write(data: ByteArray)

    suspend fun resize(columns: Int, rows: Int)

    suspend fun interrupt()

    suspend fun close()
}
