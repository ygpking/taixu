package top.wkbin.taixu.harness.mcp

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpToolInfo
import kotlin.time.Duration.Companion.milliseconds

/** STDIO 传输层故障（子进程死亡 / EOF / IO 错误）：连接不可复用，需要销毁重建 */
internal class McpStdioChannelException(message: String) : IOException(message)

/** server 正常返回的 JSON-RPC error 响应（unknown tool / invalid params 等）：连接本身健康，错误应作为结果传回调用方 */
internal class McpJsonRpcErrorException(val code: Int, message: String) :
    IllegalStateException("MCP JSON-RPC $code: $message")

/**
 * Reusable newline-framed JSON-RPC sessions for stateful STDIO MCP servers.
 *
 * Process lifecycle is delegated to [McpStdioChannelFactory]; unit tests can inject an
 * in-memory factory to exercise idle reaping, fail-fast cooldown, ignore-frame thresholds,
 * and process death recovery without a real PRoot subprocess.
 */
@Singleton
class McpStdioTransport @Inject constructor(
    private val json: Json,
    private val commandBuilder: McpCommandBuilder,
    private val channelFactory: McpStdioChannelFactory,
) : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, Connection>()
    private val startupMutexes = ConcurrentHashMap<String, Mutex>()
    private val downUntil = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS.milliseconds)
                runCatching { sweepIdleOnce(System.currentTimeMillis()) }
            }
        }
    }

    override suspend fun check(server: McpServerConfig): Boolean = try {
        connection(server, bypassCooldown = true).withInitialized { true }
    } catch (cancellation: CancellationException) {
        // B2: 取消（如 checkConnection 8s 超时、等待 mutex 被取消）不代表进程损坏，不销毁连接，
        // 否则会误杀在飞的 tools/call（其可能正持有 Connection.mutex 最长 600s）
        throw cancellation
    } catch (t: Throwable) {
        // B2: 仅传输层故障（进程退出/EOF/IO）才销毁；server 返回错误响应等本次探测失败保留连接
        if (isChannelFailure(t)) discardConnection(server.id)
        false
    }

    override suspend fun discover(server: McpServerConfig): List<McpToolInfo> = try {
        connection(server).withInitialized {
            val response = request("tools/list", JsonObject(emptyMap()))
            val result = response.result?.let {
                json.decodeFromJsonElement(McpToolsListResponse.serializer(), it)
            } ?: error("MCP tools/list did not return a result")
            result.tools.map { dto -> dto.toInfo(server, json.encodeToString(JsonObject.serializer(), dto.inputSchema)) }
        }
    } catch (t: Throwable) {
        // B2: 取消（含发现总超时）与业务错误响应不销毁连接，仅传输层故障销毁
        if (t !is CancellationException && isChannelFailure(t)) discardConnection(server.id)
        throw t
    }

    override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> =
        try {
            connection(server).withInitialized {
                val params = json.encodeToJsonElement(McpCallToolParams.serializer(), McpCallToolParams(toolName, arguments))
                val response = request("tools/call", params)
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpCallToolResult.serializer(), it)
                } ?: error("MCP tools/call did not return a result")
                !result.isError to result.content.joinToString("\n") { it.text.orEmpty() }
                    .ifBlank { if (result.isError) "执行失败" else "执行成功" }
            }
        } catch (e: McpJsonRpcErrorException) {
            // B5: server 返回的 JSON-RPC 错误响应是正常协议行为，连接健康；
            // 错误作为结果返回给模型，而不是销毁子进程重启（丢失有状态上下文）
            false to (e.message ?: "MCP JSON-RPC 错误")
        } catch (cancellation: CancellationException) {
            // B2: 调用被取消不代表进程损坏，保留连接
            throw cancellation
        } catch (t: Throwable) {
            // B5: 仅传输层失败（EOF/IO/进程死亡）才 discard，其余（序列化/业务异常等）透传且保留连接
            if (isChannelFailure(t)) discardConnection(server.id)
            throw t
        }

    /** 是否为传输层故障（子进程死亡 / EOF / IO 错误），只有此类失败才值得销毁重建连接 */
    private fun isChannelFailure(t: Throwable): Boolean =
        t is McpStdioChannelException || t is IOException

    suspend fun closeConnection(serverId: String) {
        downUntil.remove(serverId)
        discardConnection(serverId)
    }

    private suspend fun discardConnection(serverId: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            connections.remove(serverId)?.close()
        }
    }

    internal suspend fun sweepIdleOnce(nowMs: Long): Int {
        var closed = 0
        val entries = connections.entries.toList()
        for ((id, connection) in entries) {
            if (connection.inFlight) continue
            if (nowMs - connection.lastActivityMs < IDLE_TIMEOUT_MS) continue
            // B8: 与 connection() 的获取路径互斥（startupMutex）后二次确认 inFlight 与活跃时间，
            // 消除"新请求刚从 map 拿到连接、尚未锁 Connection.mutex"被清扫误关的 TOCTOU：
            // 新请求要么先在锁内 markActive（此处复查到新活跃时间即跳过），要么等锁释放后拿到新连接
            val removed = startupMutexes.getOrPut(id) { Mutex() }.withLock {
                !connection.inFlight &&
                    nowMs - connection.lastActivityMs >= IDLE_TIMEOUT_MS &&
                    connections.remove(id, connection)
            }
            if (removed) {
                connection.close()
                closed++
            }
        }
        return closed
    }

    private suspend fun connection(server: McpServerConfig, bypassCooldown: Boolean = false): Connection =
        startupMutexes.getOrPut(server.id) { Mutex() }.withLock {
            connectionLocked(server, bypassCooldown)
        }

    private suspend fun connectionLocked(server: McpServerConfig, bypassCooldown: Boolean): Connection {
        connections[server.id]?.takeIf { it.channel.isAlive && it.fingerprint == commandBuilder.fingerprint(server) }?.let {
            it.markActive()
            return it
        }
        val now = System.currentTimeMillis()
        if (!bypassCooldown) {
            val until = downUntil[server.id]
            if (until != null && now < until) {
                throw IllegalStateException(
                    "MCP[" + server.name + "] 沙箱会话拉起冷却中（剩余 " + (until - now) / 1000 + "s），跳过本次连接",
                )
            }
        }
        connections.remove(server.id)?.close()
        val channel = try {
            withTimeoutOrNull(STARTUP_TIMEOUT_MS.milliseconds) {
                channelFactory.open(server)
            } ?: run {
                downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                error("MCP 沙箱会话启动超时（" + STARTUP_TIMEOUT_MS / 1000 + "s）：" + server.command)
            }
        } catch (t: Throwable) {
            downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            throw t
        }
        downUntil.remove(server.id)
        return Connection(channel, commandBuilder.fingerprint(server)).also { connection ->
            connections[server.id] = connection
        }
    }

    private inner class Connection(
        val channel: McpStdioChannel,
        val fingerprint: String,
    ) {
        private val mutex = Mutex()
        val inFlight: Boolean get() = mutex.isLocked
        private var initialized = false

        @Volatile
        internal var lastActivityMs: Long = System.currentTimeMillis()

        fun markActive() {
            lastActivityMs = System.currentTimeMillis()
        }

        suspend fun <T> withInitialized(block: suspend Connection.() -> T): T = mutex.withLock {
            markActive()
            if (!initialized) {
                val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
                val response = requestUnlocked("initialize", params)
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpInitializeResult.serializer(), it)
                } ?: error("MCP initialize did not return a result")
                require(result.protocolVersion.isNotBlank())
                notifyUnlocked("notifications/initialized")
                initialized = true
            }
            block()
        }

        suspend fun request(method: String, params: kotlinx.serialization.json.JsonElement) =
            requestUnlocked(method, params, CALL_REQUEST_TIMEOUT_MS)

        private suspend fun requestUnlocked(
            method: String,
            params: kotlinx.serialization.json.JsonElement,
            timeoutMs: Long = REQUEST_TIMEOUT_MS,
        ): JsonRpcResponse {
            markActive()
            val id = UUID.randomUUID().toString()
            writeLine(json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params)))
            return withTimeout(timeoutMs.milliseconds) {
                var ignoredFrames = 0
                while (true) {
                    val rawLine = try {
                        channel.incoming.receive()
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        // B5/B2: 通道异常（EOF/进程退出/IO）标记为传输层故障，由上层决定销毁重建
                        throw McpStdioChannelException("MCP 请求 " + method + " 通道异常：" + t.message)
                    }
                    val element = runCatching { json.parseToJsonElement(rawLine) }.getOrNull()
                    if (element == null) {
                        ignoredFrames++
                        // 输出持续不可解析说明通道已"中毒"，后续请求同样无法工作，按传输层故障处理
                        if (ignoredFrames > MAX_IGNORED_FRAMES) throw McpStdioChannelException("MCP 输出了过多无效 JSON 行")
                        continue
                    }
                    if (element is JsonObject && element.containsKey("method")) {
                        ignoredFrames++
                        if (ignoredFrames > MAX_IGNORED_FRAMES) throw McpStdioChannelException("MCP 输出了过多请求回显或通知")
                        continue
                    }
                    val parsed = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), element) }.getOrNull()
                    if (parsed?.id == id) {
                        // B5: server 的 JSON-RPC error 响应用专用异常承载，调用方将其作为结果返回而非传输故障
                        parsed.error?.let { throw McpJsonRpcErrorException(it.code, it.message) }
                        return@withTimeout parsed
                    }
                    ignoredFrames++
                    if (ignoredFrames > MAX_IGNORED_FRAMES) throw McpStdioChannelException("MCP 未返回当前请求的响应（id=" + id + "）")
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }

        private suspend fun notifyUnlocked(method: String) =
            writeLine(json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method)))

        private suspend fun writeLine(payload: String) = channel.writeLine(payload)

        suspend fun close() {
            runCatching { channel.close() }
        }
    }


    internal fun injectConnectionForTest(server: McpServerConfig, channel: McpStdioChannel) {
        connections[server.id] = Connection(channel, commandBuilder.fingerprint(server))
    }

    companion object {
        internal const val STARTUP_TIMEOUT_MS = 3500L
        private const val FAILURE_COOLDOWN_MS = 3L * 60L * 1000L
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val CALL_REQUEST_TIMEOUT_MS = 600_000L
        internal const val MAX_BUFFERED_LINES = 64
        internal const val MAX_FRAME_CHARS = 1 * 1024 * 1024
        internal const val MAX_IGNORED_FRAMES = 256
        internal const val IDLE_TIMEOUT_MS = 10L * 60L * 1000L
        internal const val SWEEP_INTERVAL_MS = 60L * 1000L
    }
}
