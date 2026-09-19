package com.taixu.core.ui.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI 性能优化：流量整形器
 *
 * **问题背景**：Harness 层高频发射状态（如逐字输出、实时日志），导致 Compose 过度重组，
 * 引起 UI 卡顿甚至崩溃（Recomposition too frequent）。
 *
 * **解决方案**：将高频小数据包合并为低频大数据包，限制最大重组频率。
 *
 * @param timeoutMillis 时间窗口（毫秒），在此时间内收集的发射会被合并
 * @param maxEmissions 最大合并数量，达到此数量立即发射
 *
 * **使用示例**：
 * ```kotlin
 * // Harness 层每秒可能发射 20 次状态
 * val harnessStateFlow = harnessViewModel.stateFlow
 *
 * // 优化后：最多每 100ms 更新一次 UI，或累积 10 个状态更新一次
 * harnessStateFlow
 *     .throttleLatest(timeoutMillis = 100, maxEmissions = 10)
 *     .collect { states ->
 *         // states 是 List<HarnessState>，包含最近 100ms 内的所有状态
 *         uiState.value = states.last() // 只取最新状态渲染
 *     }
 * ```
 *
 * 实现说明：collect 回调与定时器协程并发访问缓冲，此前用普通 ArrayList 无同步
 * （ConcurrentModificationException 风险），且下游过慢时 trySend 失败静默丢数据。
 * 现改为 Mutex 保护 + 挂起式 send：下游过慢时背压到上游而不是丢弃。
 */
fun <T> Flow<T>.throttleLatest(
    timeoutMillis: Long = 100,
    maxEmissions: Int = 10
): Flow<List<T>> = channelFlow {
    val mutex = Mutex()
    val buffer = mutableListOf<T>()

    suspend fun sendBuffer() {
        val batch = mutex.withLock {
            if (buffer.isEmpty()) null else buffer.toList().also { buffer.clear() }
        } ?: return
        send(batch)
    }

    // 周期冲刷器：缓冲非空时每个窗口 flush 一次，等效"首个元素后 timeoutMillis 内合并"
    val flusher = launch {
        while (isActive) {
            delay(timeoutMillis)
            sendBuffer()
        }
    }

    try {
        collect { value ->
            val size = mutex.withLock {
                buffer.add(value)
                buffer.size
            }
            if (size >= maxEmissions) sendBuffer()
        }
    } finally {
        flusher.cancel()
        sendBuffer()
    }
}

/**
 * 专门用于文本流（Typewriter effect）的节流
 *
 * **问题背景**：AI 逐字输出时，每个字符都触发 UI 重组，导致严重卡顿。
 *
 * **解决方案**：将字符流合并为字符串块，每 50ms 或每 20 个字符更新一次 UI。
 *
 * **使用示例**：
 * ```kotlin
 * // Harness 层逐字输出
 * val charFlow = aiResponse.charFlow()
 *
 * // 优化后：每 50ms 或每 20 个字符更新一次 UI
 * charFlow
 *     .throttleText(timeoutMillis = 50, chunkSize = 20)
 *     .collect { textChunk ->
 *         messageText.value += textChunk
 *     }
 * ```
 */
fun Flow<Char>.throttleText(
    timeoutMillis: Long = 50,
    chunkSize: Int = 20
): Flow<String> = channelFlow {
    val mutex = Mutex()
    val buffer = StringBuilder()

    suspend fun sendBuffer() {
        val chunk = mutex.withLock {
            if (buffer.isEmpty()) null else buffer.toString().also { buffer.clear() }
        } ?: return
        send(chunk)
    }

    val flusher = launch {
        while (isActive) {
            delay(timeoutMillis)
            sendBuffer()
        }
    }

    try {
        collect { char ->
            val length = mutex.withLock {
                buffer.append(char)
                buffer.length
            }
            if (length >= chunkSize) sendBuffer()
        }
    } finally {
        flusher.cancel()
        sendBuffer()
    }
}
