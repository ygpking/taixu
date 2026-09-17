package top.wkbin.taixu.harness.projection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.taixu.core.ui.util.throttleLatest
import top.wkbin.taixu.harness.HarnessMessage

/**
 * Harness 层与 UI 层之间的性能缓冲器
 * 
 * **问题背景**：
 * 1. Harness 层高频发射状态（如逐字输出、实时日志），每秒可达 20-50 次
 * 2. Compose UI 对每次 StateFlow 变化都会触发重组，导致严重卡顿
 * 3. 长列表消息（超过 100 条）时，整列复制 + 重组可能导致 ANR 或崩溃
 * 
 * **解决方案**：
 * 1. 在 Harness 层内部使用节流策略，限制发射频率
 * 2. 仅对前台会话应用节流，后台会话保持完整数据
 * 3. 使用增量更新替代整列复制
 * 
 * @param throttleWindowMs 节流时间窗口（毫秒），默认 100ms
 * @param maxEmissions 最大累积发射数，达到此数立即发送
 */
@Singleton
class HarnessUiBuffer @Inject constructor(
    private val projector: SessionMessageProjector,
) {
    
    /**
     * 经过节流的前台消息流
     * 
     * 原始流：projector.foregroundMessages（可能每秒发射 50 次）
     * 节流流：throttledForegroundMessages（最多每 100ms 发射一次）
     */
    private val _throttledForegroundMessages = MutableStateFlow<List<HarnessMessage>>(emptyList())
    val throttledForegroundMessages: StateFlow<List<HarnessMessage>> = _throttledForegroundMessages.asStateFlow()
    
    /**
     * 启动节流监听
     * 
     * 使用示例：
     * ```kotlin
     * // 在 ViewModel 或 Application 中调用一次
     * harnessUiBuffer.startThrottling(throttleWindowMs = 100, maxEmissions = 10)
     * 
     * // UI 层观察节流后的流
     * viewModel.throttledMessages.collect { messages ->
     *     LazyColumn { items(messages) { message -> ... } }
     * }
     * ```
     */
    suspend fun startThrottling(
        throttleWindowMs: Long = 100,
        maxEmissions: Int = 10
    ) {
        // 将原始高频流转换为节流流
        projector.foregroundMessages
            .throttleLatest(timeoutMillis = throttleWindowMs, maxEmissions = maxEmissions)
            .collect { batchedLists ->
                // batchedLists 是 List<List<HarnessMessage>>，取最新的列表
                _throttledForegroundMessages.update { batchedLists.lastOrNull() ?: emptyList() }
            }
    }
    
    /**
     * 直接获取当前节流后的消息快照
     * 适用于不需要 Flow 的场景
     */
    fun getThrottledSnapshot(): List<HarnessMessage> {
        return _throttledForegroundMessages.value
    }
}
