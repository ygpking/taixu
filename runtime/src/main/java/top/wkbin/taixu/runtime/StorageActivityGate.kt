package top.wkbin.taixu.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Commands may run concurrently; cleanup needs an idle runtime and prevents new launches. */
internal class StorageActivityGate {
    private val monitor = Any()
    private var active = 0
    private var cleaning = false

    private val _activeCount = MutableStateFlow(0)
    /**
     * 当前进行中的存储活动数量（命令执行 / 构建 / 会话启动等）。
     * 供前台服务判断沙箱是否真的在干活，从而按需持有唤醒锁，
     * 避免"沙箱空闲却一直持锁"导致息屏后 CPU 无法进入低功耗。
     */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    suspend fun <T> activity(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!cleaning) { "正在清理存储，请稍后再启动任务" }
            active++
            _activeCount.value = active
        }
        try {
            return block()
        } finally {
            synchronized(monitor) {
                active--
                _activeCount.value = active
            }
        }
    }

    suspend fun cleanup(block: suspend () -> Unit) {
        synchronized(monitor) {
            check(!cleaning && active == 0) { "有命令或构建正在运行，请结束后再清理" }
            cleaning = true
        }
        try {
            block()
        } finally {
            synchronized(monitor) { cleaning = false }
        }
    }
}
