package top.wkbin.taixu.harness.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.model.McpConnectionState
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.core.model.McpTransportType
import top.wkbin.taixu.harness.events.AgentEventLogger
import kotlin.time.Duration.Companion.milliseconds

/** Thin MCP registry coordinator; transports own protocol and process details. */
@Singleton
class McpManager @Inject constructor(
    private val repository: McpServerRepository,
    private val stdio: McpStdioTransport,
    private val http: McpHttpTransport,
    private val commandBuilder: McpCommandBuilder,
    private val logger: AppLogger,
    private val agentEventLogger: AgentEventLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class CachedTools(val fingerprint: String, val tools: List<McpToolInfo>)
    private val cache = ConcurrentHashMap<String, CachedTools>()
    private val discoveryMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastErrors = ConcurrentHashMap<String, String>()
    private val _connectionStates = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, McpConnectionState>> = _connectionStates.asStateFlow()

    init {
        // 启动时后台异步预热已启用的 MCP 服务，提前填充缓存，用户首次发消息直接 0ms 命中
        scope.launch {
            runCatching { getActiveMcpTools() }
        }
    }

    fun getLastError(serverId: String): String? = lastErrors[serverId]

    suspend fun checkConnection(server: McpServerConfig): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(server).check(server) } ?: false
    }

    suspend fun refreshConnections() = withContext(Dispatchers.IO) {
        val servers = repository.servers.first()
        servers.filterNot { it.isEnabled }.forEach { server ->
            cache.remove(server.id)
            lastErrors.remove(server.id)
            if (server.transportType == McpTransportType.STDIO) stdio.closeConnection(server.id)
        }
        _connectionStates.value = servers.associate { it.id to if (it.isEnabled) McpConnectionState.CHECKING else McpConnectionState.UNKNOWN }
        coroutineScope {
            servers.filter { it.isEnabled }.map { server ->
                launch {
                    val state = if (checkConnection(server)) McpConnectionState.ONLINE else McpConnectionState.OFFLINE
                    _connectionStates.update { it + (server.id to state) }
                }
            }.joinAll()
        }
    }

    suspend fun getActiveMcpTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val servers = repository.servers.first()
        servers.filterNot { it.isEnabled }.forEach { server ->
            cache.remove(server.id)
            if (server.transportType == McpTransportType.STDIO) stdio.closeConnection(server.id)
        }
        val enabledServers = servers.filter { it.isEnabled }
        if (enabledServers.isEmpty()) return@withContext emptyList()

        // Linux 运行时未就绪属确定性不可恢复故障：同一批发现里其余服务必然同因失败，
        // 逐路重试只会把同一段堆栈刷 N 遍（实测 5 路失败刷出 102 行）。命中即整批短路。
        val runtimeUnavailable = AtomicBoolean(false)
        coroutineScope {
            enabledServers.map { server ->
                async<List<McpToolInfo>> {
                    if (runtimeUnavailable.get()) return@async emptyList()
                    val fingerprint = fingerprint(server)
                    cache[server.id]?.takeIf { it.fingerprint == fingerprint }?.tools
                        ?: discoveryMutexes.getOrPut(server.id) { Mutex() }.withLock {
                            if (runtimeUnavailable.get()) return@withLock emptyList<McpToolInfo>()
                            cache[server.id]?.takeIf { it.fingerprint == fingerprint }?.tools ?: run {
                                // 总超时兜底：沙箱会话拉起或 MCP 进程挂起时不能阻塞每轮对话（挂起是无日志的），
                                // 超时按失败处理，本轮不注入该服务工具，下一轮重试。
                                agentEventLogger.log(DISCOVERY_LOG_SESSION, "McpDiscovery", "MCP[${server.name}] 工具发现开始（transport=${server.transportType}）")
                                val startedAt = System.currentTimeMillis()
                                cancellableResult { discoverWithTimeout(server) }.onSuccess {
                                    agentEventLogger.log(
                                        DISCOVERY_LOG_SESSION,
                                        "McpDiscovery",
                                        "MCP[${server.name}] 发现 ${it.size} 个工具，耗时 ${System.currentTimeMillis() - startedAt}ms",
                                    )
                                }.onFailure {
                                    // 确定性不可恢复故障（运行时未就绪）会在同一批里逐路重复，
                                    // 只记一行、不带堆栈；未知故障保留堆栈以便排查。
                                    val deterministic = isDeterministicUnavailable(it)
                                    agentEventLogger.log(
                                        DISCOVERY_LOG_SESSION,
                                        "McpDiscovery",
                                        "MCP[${server.name}] 工具发现失败，耗时 ${System.currentTimeMillis() - startedAt}ms：${it.message ?: it::class.simpleName}",
                                        if (deterministic) null else it,
                                    )
                                }
                            }.onSuccess {
                                cache[server.id] = CachedTools(fingerprint, it)
                                lastErrors.remove(server.id)
                                state(server.id, McpConnectionState.ONLINE)
                            }.onFailure {
                                val msg = it.message ?: "工具发现异常"
                                lastErrors[server.id] = msg
                                // 静默失败会让"模型不调用 MCP 工具"无从排查，这里必须留下线索；
                                // 冷却期内 / 确定性不可恢复的重复失败只记一行，不再打整段堆栈刷屏。
                                val inCooldown = msg.contains("冷却中")
                                val deterministic = isDeterministicUnavailable(it)
                                if (deterministic) runtimeUnavailable.set(true)
                                logger.w(
                                    "MCP[${server.name}] 工具发现失败，本轮对话不注入该服务的工具: $msg",
                                    if (inCooldown || deterministic) null else it,
                                )
                                cache.remove(server.id)
                                state(server.id, McpConnectionState.OFFLINE)
                            }.getOrDefault(emptyList())
                        }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun discoverTools(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        transport(server).discover(server)
    }

    suspend fun testServer(server: McpServerConfig): Result<List<McpToolInfo>> = withContext(Dispatchers.IO) {
        cancellableResult { discoverWithTimeout(server) }
        .onSuccess { cache[server.id] = CachedTools(fingerprint(server), it); state(server.id, McpConnectionState.ONLINE) }
        .onFailure { cache.remove(server.id); state(server.id, McpConnectionState.OFFLINE) }
    }

    suspend fun executeTool(
        fullToolName: String,
        arguments: JsonObject,
        workspace: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!fullToolName.startsWith("mcp__")) return@withContext false to "无效的 MCP 工具名称：$fullToolName"
        val tool = getActiveMcpTools().firstOrNull { McpToolApiName.matches(it, fullToolName) }
            ?: return@withContext false to "未找到 MCP 工具：$fullToolName"
        val server = repository.servers.first().firstOrNull { it.id == tool.serverId && it.isEnabled }
            ?: return@withContext false to "未找到 MCP 服务：${tool.serverId}"
        val bound = commandBuilder.bindWorkspaceRepository(server, workspace)
        return@withContext cancellableResult { transport(bound).execute(bound, tool.name, arguments) }
            .onFailure { logger.e("MCP[${server.name}] 工具 ${tool.name} 执行异常: ${it.message}", it) }
            .onSuccess { (ok, output) ->
                if (!ok) logger.w("MCP[${server.name}] 工具 ${tool.name} 返回错误: $output".take(500))
            }
            .getOrElse { false to "MCP 工具执行异常：${it.message ?: it::class.simpleName}" }
    }

    private suspend fun discoverWithTimeout(server: McpServerConfig): List<McpToolInfo> =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(server).discover(server) }
            ?: error("工具发现超时（${DISCOVERY_TIMEOUT_MS / 1000}s）：沙箱会话或 MCP 进程可能已挂起")

    /**
     * 确定性不可恢复故障（Linux 运行时未初始化，`LinuxRuntimeImpl.ensureReady()` 抛出）：
     * 重试无意义，且同一批发现里其余服务必然同因失败。命中后本轮整批短路、只留一行日志不打堆栈。
     * 沿 cause 链最多上溯 10 层，避免自引用造成死循环。
     */
    private fun isDeterministicUnavailable(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < 10) {
            val message = cause.message.orEmpty()
            if (message.contains("Linux runtime is not ready") || message.contains("Call initialize() first")) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private fun state(id: String, state: McpConnectionState) { _connectionStates.update { it + (id to state) } }
    private fun fingerprint(server: McpServerConfig): String =
        "${server.transportType}|${server.serverUrl.trim()}|${server.command}|${server.args}|${server.env.toSortedMap()}"
    private fun transport(server: McpServerConfig): McpTransport = when (server.transportType) {
        McpTransportType.STDIO -> stdio
        McpTransportType.SSE -> http
    }

    private companion object {
        /** 单服务器工具发现总超时：覆盖沙箱会话拉起 + initialize + tools/list，超时即本轮跳过注入。 */
        const val DISCOVERY_TIMEOUT_MS = 8_000L

        /** 工具发现无会话上下文，agent 事件日志用占位 sessionId。 */
        const val DISCOVERY_LOG_SESSION = "-"
    }
}
