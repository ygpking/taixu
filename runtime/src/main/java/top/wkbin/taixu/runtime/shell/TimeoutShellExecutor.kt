package top.wkbin.taixu.runtime.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ⏱️ 带超时熔断的 Shell 执行器
 * 
 * 功能：
 * 1. 执行 shell 命令并设置超时时间（默认 30 秒）
 * 2. 超时时自动终止进程并清理资源
 * 3. 捕获 stdout/stderr 输出
 * 4. 返回退出码和执行结果
 * 
 * 用法示例：
 * ```kotlin
 * @Inject lateinit var shellExecutor: TimeoutShellExecutor
 * 
 * // 执行命令（默认 30 秒超时）
 * val result = shellExecutor.execute("ls -la /data/local/tmp")
 * 
 * // 自定义超时时间
 * val result = shellExecutor.execute(
 *     command = "slow_command.sh",
 *     timeout = 60.seconds,
 *     workingDir = File("/data/local/tmp")
 * )
 * 
 * if (result.isSuccess) {
 *     println("Output: ${result.output}")
 * } else {
 *     println("Error: ${result.error}")
 * }
 * ```
 */
@Singleton
class TimeoutShellExecutor @Inject constructor() {

    /**
     * 执行 shell 命令（默认超时 30 秒）
     * 
     * @param command 要执行的命令
     * @param timeout 超时时间
     * @param workingDir 工作目录
     * @param environment 环境变量
     * @return ShellExecutionResult
     */
    suspend fun execute(
        command: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        workingDir: java.io.File? = null,
        environment: Map<String, String>? = null
    ): ShellExecutionResult {
        return try {
            executeWithTimeout(command, timeout, workingDir, environment)
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "Command timed out after $timeout: $command")
            ShellExecutionResult(
                exitCode = -1,
                output = "",
                error = "Command timed out after ${timeout.inWholeSeconds} seconds",
                isTimeout = true
            )
        } catch (e: IOException) {
            android.util.Log.e(TAG, "IO error executing command: $command", e)
            ShellExecutionResult(
                exitCode = -1,
                output = "",
                error = "IO error: ${e.message}",
                isTimeout = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Unexpected error executing command: $command", e)
            ShellExecutionResult(
                exitCode = -1,
                output = "",
                error = "Unexpected error: ${e.message}",
                isTimeout = false
            )
        }
    }

    /**
     * 内部实现：带超时的命令执行
     */
    private suspend fun executeWithTimeout(
        command: String,
        timeout: Duration,
        workingDir: java.io.File?,
        environment: Map<String, String>?
    ): ShellExecutionResult {
        return withTimeout(timeout.inWholeMilliseconds) {
            withContext(Dispatchers.IO) {
                val processBuilder = ProcessBuilder("sh", "-c", command)
                
                // 设置工作目录
                workingDir?.let { processBuilder.directory(it) }
                
                // 设置环境变量
                environment?.let { env ->
                    val envMap = processBuilder.environment()
                    envMap.putAll(env)
                }
                
                // 禁用继承当前进程的输入输出（避免污染）
                processBuilder.redirectErrorStream(true)
                
                val process = processBuilder.start()
                
                // 创建超时监控线程
                val timeoutMonitor = Thread {
                    try {
                        // 在后台等待超时，如果主协程已取消则跳过
                        Thread.sleep(timeout.inWholeMilliseconds + 100)
                        if (process.isAlive) {
                            android.util.Log.w(TAG, "Force destroying timed out process: $command")
                            process.destroyForcibly()
                        }
                    } catch (e: InterruptedException) {
                        // 正常中断，无需处理
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                
                try {
                    // 读取输出
                    val outputBuilder = StringBuilder()
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            outputBuilder.appendLine(line)
                        }
                    }
                    
                    // 等待进程结束
                    val exitCode = process.waitFor()
                    
                    // 取消超时监控
                    timeoutMonitor.interrupt()
                    
                    val output = outputBuilder.toString().trim()
                    
                    ShellExecutionResult(
                        exitCode = exitCode,
                        output = if (exitCode == 0) output else "",
                        error = if (exitCode != 0) output else "",
                        isTimeout = false
                    )
                } catch (e: InterruptedException) {
                    // 进程被中断，强制销毁
                    process.destroyForcibly()
                    timeoutMonitor.interrupt()
                    
                    throw e
                }
            }
        }
    }

    /**
     * 批量执行多个命令（串行）
     * 
     * @param commands 命令列表
     * @param timeoutPerCommand 每个命令的超时时间
     * @param stopOnError 遇到错误是否停止
     * @return 所有命令的执行结果
     */
    suspend fun executeBatch(
        commands: List<String>,
        timeoutPerCommand: Duration = DEFAULT_TIMEOUT,
        stopOnError: Boolean = true
    ): List<ShellExecutionResult> {
        val results = mutableListOf<ShellExecutionResult>()
        
        for (command in commands) {
            val result = execute(command, timeoutPerCommand)
            results.add(result)
            
            if (stopOnError && !result.isSuccess) {
                android.util.Log.w(TAG, "Batch execution stopped at command: $command")
                break
            }
        }
        
        return results
    }

    /**
     * 检查命令是否存在
     * 
     * @param commandName 命令名称（如 "git", "python3"）
     * @return 是否存在
     */
    suspend fun commandExists(commandName: String): Boolean {
        val result = execute("command -v $commandName")
        return result.isSuccess && result.output.isNotBlank()
    }

    companion object {
        private const val TAG = "TimeoutShellExecutor"
        private val DEFAULT_TIMEOUT = 30.seconds
    }
}

/**
 * Shell 执行结果
 * 
 * @property exitCode 退出码（0 表示成功，-1 表示异常）
 * @property output 标准输出（成功时）
 * @property error 错误输出（失败时）
 * @property isTimeout 是否因超时失败
 */
data class ShellExecutionResult(
    val exitCode: Int,
    val output: String,
    val error: String,
    val isTimeout: Boolean
) {
    /** 是否执行成功 */
    val isSuccess: Boolean
        get() = exitCode == 0 && !isTimeout
    
    /** 获取输出或错误信息 */
    fun getOutputOrError(): String {
        return if (isSuccess) output else error
    }
}

// 导入 withContext
import kotlinx.coroutines.withContext
