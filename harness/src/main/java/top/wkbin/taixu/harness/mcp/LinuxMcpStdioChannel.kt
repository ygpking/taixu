package top.wkbin.taixu.harness.mcp

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.SessionConfig

/**
 * Production factory: starts a Linux PTY session, wraps it in a [McpStdioChannel], and pumps
 * its stdout into a buffered line channel. Bounded startup timeout prevents the caller from
 * hanging when PRoot is wedged.
 */
@Singleton
class LinuxMcpStdioChannelFactory @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val commandBuilder: McpCommandBuilder,
) : McpStdioChannelFactory {
    override suspend fun open(server: McpServerConfig): McpStdioChannel {
        if (!awaitLinuxRuntimeReady(linuxRuntime.state)) {
            throw IllegalStateException("Linux runtime is not ready. Call initialize() first.")
        }
        val session = linuxRuntime.startSession(
            SessionConfig(
                workingDirectory = "/root",
                environment = server.env,
                commandLine = commandBuilder.commandLine(server),
                allowSttyResize = false,
                // 协议长连接不按"会话存活"计忙：仅请求在飞时经 withSandboxActivity 上报忙碌，
                // 否则常驻 MCP 连接会把沙箱唤醒锁永久钉住（详见 SessionConfig.countsAsSandboxBusy）。
                countsAsSandboxBusy = false,
            ),
        )
        return LinuxMcpStdioChannel(server.id, session, runtime = linuxRuntime)
    }
}

class LinuxMcpStdioChannel(
    private val serverId: String,
    private val session: LinuxSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameChars: Int = McpStdioTransport.MAX_FRAME_CHARS,
    private val bufferCapacity: Int = McpStdioTransport.MAX_BUFFERED_LINES,
    /** 用于上报请求-响应窗口的沙箱瞬态活动；内存替身可为 null（此时不改变忙碌信号）。 */
    private val runtime: LinuxRuntime? = null,
) : McpStdioChannel {
    override suspend fun <T> withSandboxActivity(block: suspend () -> T): T =
        runtime?.withSandboxActivity(block) ?: block()
    // 必须无界：两次请求之间没有消费者，有界通道一旦被服务端自发输出
    // （进度通知/日志误写 stdout/banner 重放）填满，send 挂起 → 无人再读 PTY →
    // 子进程写 stdout 阻塞、整个 server 冻结，下一次请求只能等 600s 超时。
    // 单帧 1MB 上限已由 maxFrameChars 保证，空闲连接由上层 IDLE_TIMEOUT 回收。
    private val lines: Channel<String> = Channel(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<String> = lines
    override val isAlive: Boolean get() = session.isAlive

    init {
        scope.launch {
            val buf = StringBuilder()
            try {
                session.output.collect { output ->
                    val chunk = output.text
                    var start = 0
                    while (start < chunk.length) {
                        val newline = chunk.indexOf('\n', start)
                        val end = if (newline >= 0) newline else chunk.length
                        val partLength = end - start
                        if (buf.length + partLength > maxFrameChars) {
                            throw IllegalStateException("MCP STDIO frame is too large")
                        }
                        buf.append(chunk, start, end)
                        if (newline < 0) break
                        val line = buf.toString().trim()
                        buf.clear()
                        if (line.startsWith("{")) lines.send(line)
                        start = newline + 1
                    }
                }
                lines.close()
            } catch (t: Throwable) {
                lines.close(t)
                runCatching { session.close() }
            }
        }
    }

    override suspend fun writeLine(line: String) = session.write((line + "\n").toByteArray(Charsets.UTF_8))

    override suspend fun close() {
        runCatching { session.close() }
    }
}
