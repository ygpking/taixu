package top.wkbin.taixu.runtime

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.model.InstalledDistro
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.InteractiveLaunchSpec
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface LinuxRuntime {
    val state: StateFlow<RuntimeState>
    val activeDistroId: StateFlow<String>
    val installedDistros: StateFlow<List<InstalledDistro>>

    /**
     * 沙箱当前是否正有任务在跑（命令执行、构建、会诊会话、后台服务均在统计内）。
     * 前台保活服务据此按需持有 CPU 唤醒锁：有任务才持锁，空闲即释放，
     * 避免息屏后 CPU 被长期钉住无法进入低功耗。
     * 默认实现恒为 false，测试替身无需感知。
     */
    val sandboxBusy: StateFlow<Boolean> get() = ALWAYS_IDLE

    /**
     * 上报一段"瞬态沙箱活动"（典型场景：MCP 请求-响应窗口）。
     * 活动期间 [sandboxBusy] 为 true，前台保活服务据此重新持锁；block 结束即撤销。
     * 用于补齐"长连接协议会话不计常驻忙碌、但处理请求时确实要占用沙箱"的中间态：
     * 会话本身挂着不算忙，只有请求真的在飞时才算忙。
     * 默认实现直接执行 block，测试替身无需感知。
     */
    suspend fun <T> withSandboxActivity(block: suspend () -> T): T = block()

    suspend fun initialize(request: RuntimeInstallRequest = RuntimeInstallRequest("ubuntu")): AppResult<Unit>
    suspend fun restoreInstalledState(): Boolean
    suspend fun updateRootfs(distroId: String? = null): AppResult<Unit>
    suspend fun checkRootfsUpdate(distroId: String? = null): AppResult<RootfsUpdateInfo>
    suspend fun healthCheck(distroId: String? = null): RuntimeHealth

    suspend fun switchActiveDistro(distroId: String): AppResult<Unit>
    suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit = {}): AppResult<Unit>
    suspend fun importDistro(request: RuntimeInstallRequest, archive: File): AppResult<Unit> =
        AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(
            top.wkbin.taixu.core.common.result.ErrorCode.INSTALLATION_FAILED,
            "当前运行时不支持导入 RootFS",
        ))
    suspend fun uninstallDistro(distroId: String): AppResult<Unit>
    /** Remove the active distro and its installed tools while preserving /workspace. */
    suspend fun resetSandbox(distroId: String? = null): AppResult<Unit>
    fun refreshInstalledDistros()

    suspend fun execute(command: ShellCommand, distroId: String? = null): CommandResult
    suspend fun startSession(config: SessionConfig = SessionConfig(), distroId: String? = null): LinuxSession

    /**
     * Build host argv/env for an interactive console owned by Termux TerminalSession JNI,
     * without opening our NativePty. Used by the matrix terminal UI.
     */
    suspend fun buildInteractiveLaunch(
        config: SessionConfig = SessionConfig(),
        distroId: String? = null,
    ): InteractiveLaunchSpec = error("当前运行时不支持交互式终端启动规格")

    suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
    ): ManagedProcess

    suspend fun stopBackground(id: String): Boolean
    fun listBackground(): List<ManagedProcess>
    suspend fun cleanupDeadBackground(): Int
    fun observeBackgroundLogs(idOrToolId: String): kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.emptyFlow()
    fun getBackgroundLogs(idOrToolId: String): List<String> = emptyList()
    fun clearBackgroundLogs(idOrToolId: String) = Unit

    suspend fun shutdown()
    /** Execute file cleanup only while runtime launches and installation are excluded. */
    suspend fun withStorageCleanup(block: suspend () -> Unit) {
        error("当前运行时不支持安全存储清理")
    }
    fun rootfsPath(distroId: String? = null): File
    fun rootfsVersion(distroId: String? = null): String? = null
    fun workspacePath(): File
}

/** 默认空闲信号：测试替身等实现无需感知，恒为 false。 */
private val ALWAYS_IDLE: StateFlow<Boolean> = MutableStateFlow(false)
