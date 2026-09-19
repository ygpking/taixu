package top.wkbin.taixu.runtime.shell

import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProcessType {
    TERMINAL,
    SERVICE,
    COMMAND,
    UNKNOWN,
}

data class ManagedProcess(
    val id: String,
    val startedAt: Long,
    val session: LinuxSession,
    val toolId: String? = null,
    val pid: Long? = session.pid,
    val type: ProcessType = ProcessType.UNKNOWN,
)

interface ProcessRegistry {
    suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
        mounts: List<StorageMountBinding> = emptyList(),
    ): ManagedProcess
    suspend fun stop(id: String): Boolean
    suspend fun stopAll()
    suspend fun cleanupDeadProcesses(): Int
    fun list(): List<ManagedProcess>
    fun observeLogs(idOrToolId: String): Flow<List<String>>
    fun getLogs(idOrToolId: String): List<String>
    fun clearLogs(idOrToolId: String)
}

@Singleton
class ProcessRegistryImpl @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val prootCommandBuilder: ProotCommandBuilder,
) : ProcessRegistry {
    private val mutex = Mutex()
    // list()/getLogs() 在 UI 与 harness 线程无锁读取，与 mutex 内的写路径并发；
    // LinkedHashMap 会在并发读写下抛 ConcurrentModificationException 甚至损坏结构，
    // 必须用并发容器。展示顺序由 list() 内显式排序保证（见下）。
    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logsMap = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    private fun getOrCreateLogFlow(key: String): MutableStateFlow<List<String>> =
        logsMap.computeIfAbsent(key) { MutableStateFlow(emptyList()) }

    private fun appendLog(key: String, line: String) {
        // StateFlow.value 读-改-写在多协程日志并发下会互相覆盖丢行，必须原子更新
        getOrCreateLogFlow(key).update { current ->
            if (current.size >= 500) {
                current.drop(current.size - 499) + line
            } else {
                current + line
            }
        }
    }

    override suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
        mounts: List<StorageMountBinding>,
    ): ManagedProcess = mutex.withLock {
        processes.remove(id)?.session?.close()
        val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
            ?: pathManager.listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        val session = ProcessLinuxSession(
            prootCommandBuilder.build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = pathManager.rootfsDir(safeDistro),
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = pathManager.taixuRootDir(safeDistro),
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
            ),
            hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
        )

        val logKey = toolId ?: id
        appendLog(logKey, "[TaiXu] 正在启动服务进程...")
        if (toolId != null && toolId != id) {
            appendLog(id, "[TaiXu] 正在启动服务进程...")
        }

        scope.launch {
            try {
                session.output.collect { terminalOutput ->
                    terminalOutput.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            appendLog(logKey, line)
                            if (toolId != null && toolId != id) {
                                appendLog(id, line)
                            }
                        }
                }
            } catch (_: Exception) {
            } finally {
                val exitNotice = "[TaiXu] 服务进程已停止"
                appendLog(logKey, exitNotice)
                if (toolId != null && toolId != id) {
                    appendLog(id, exitNotice)
                }
            }
        }

        ManagedProcess(
            id = id,
            startedAt = System.currentTimeMillis(),
            session = session,
            toolId = toolId,
            pid = session.pid,
            type = type,
        ).also { processes[id] = it }
    }

    override suspend fun stop(id: String): Boolean = mutex.withLock {
        val process = processes.remove(id) ?: return@withLock false
        process.session.close()
        true
    }

    override suspend fun stopAll() = mutex.withLock {
        processes.values.forEach { it.session.close() }
        processes.clear()
    }

    override suspend fun cleanupDeadProcesses(): Int = mutex.withLock {
        val dead = processes.values.filter { !it.session.isAlive }
        dead.forEach { processes.remove(it.id) }
        dead.size
    }

    override fun list(): List<ManagedProcess> =
        // ConcurrentHashMap 无稳定顺序；此前 LinkedHashMap 按插入序展示，
        // 用 startedAt（+id 决胜）排序保持等价且确定的行为
        processes.values.sortedWith(compareBy({ it.startedAt }, { it.id }))

    override fun observeLogs(idOrToolId: String): Flow<List<String>> =
        getOrCreateLogFlow(idOrToolId).asStateFlow()

    override fun getLogs(idOrToolId: String): List<String> =
        getOrCreateLogFlow(idOrToolId).value

    override fun clearLogs(idOrToolId: String) {
        getOrCreateLogFlow(idOrToolId).value = emptyList()
    }
}
