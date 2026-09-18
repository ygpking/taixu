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
            ),
        )
        return LinuxMcpStdioChannel(server.id, session)
    }
}

class LinuxMcpStdioChannel(
    private val serverId: String,
    private val session: LinuxSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameChars: Int = McpStdioTransport.MAX_FRAME_CHARS,
    private val bufferCapacity: Int = McpStdioTransport.MAX_BUFFERED_LINES,
) : McpStdioChannel {
    private val lines: Channel<String> = Channel(capacity = bufferCapacity)
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
