package com.taixu.core.ui.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

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
 */
fun <T> Flow<T>.throttleLatest(
    timeoutMillis: Long = 100,
    maxEmissions: Int = 10
): Flow<List<T>> = channelFlow {
    val buffer = mutableListOf<T>()
    
    fun sendBuffer() {
        if (buffer.isNotEmpty()) {
            trySend(buffer.toList())
            buffer.clear()
        }
    }

    collect { value ->
        buffer.add(value)
        
        // 达到阈值立即发送
        if (buffer.size >= maxEmissions) {
            sendBuffer()
        } else if (buffer.size == 1) {
            // 第一个元素到达，启动定时器
            launch {
                kotlinx.coroutines.delay(timeoutMillis)
                sendBuffer()
            }
        }
    }
}

/**
 * 专门用于文本流（Typewriter effect）的节流
 * 
 * **问题背景**：AI 逐字输出时，每个字符都触发 UI 重组，导致严重卡顿。
 * 
 * **解决方案**：将字符流合并为字符串块，每 50ms 或每 20 个字符更新一次 UI。
 * 
 * @param timeoutMillis 时间窗口（毫秒）
 * @param chunkSize 每次更新的字符数
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
    val buffer = StringBuilder()
    
    fun sendBuffer() {
        if (buffer.isNotEmpty()) {
            trySend(buffer.toString())
            buffer.clear()
        }
    }

    collect { char ->
        buffer.append(char)
        
        if (buffer.length >= chunkSize) {
            sendBuffer()
        } else if (buffer.length == 1) {
            launch {
                kotlinx.coroutines.delay(timeoutMillis)
                sendBuffer()
            }
        }
    }
}
