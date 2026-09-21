package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.channels.ReceiveChannel
import top.wkbin.taixu.core.model.McpServerConfig

/**
 * Lifecycle boundary for an MCP STDIO subprocess. Production code wraps a LinuxSession;
 * unit tests inject an in-memory implementation so request dispatch, frame filtering and
 * reaping can be exercised without a real PRoot.
 */
interface McpStdioChannel {
    val isAlive: Boolean
    suspend fun writeLine(line: String)
    suspend fun close()
    /** Lines arriving on the subprocess stdout, already newline-stripped. Channel is closed when the process dies. */
    val incoming: ReceiveChannel<String>

    /**
     * 把 [block]（一次请求-响应往返）标记为"沙箱瞬态活动"：期间 Linux 沙箱计入忙碌，
     * 前台保活服务据此持 CPU 唤醒锁；返回后活动撤销。
     *
     * MCP STDIO 是常驻长连接，绝大多数时间只是挂着等请求，因此不能按"会话存活"计忙
     * （否则唤醒锁永不放）。真正需要 CPU 的只有请求在飞的这段时间，由本方法精确上报。
     * 默认直接执行 [block]：内存测试替身与不支持长连接的通道无需感知。
     */
    suspend fun <T> withSandboxActivity(block: suspend () -> T): T = block()
}

/** Builds MCP STDIO channels. Lets tests inject an in-memory factory. */
interface McpStdioChannelFactory {
    suspend fun open(server: McpServerConfig): McpStdioChannel
}
