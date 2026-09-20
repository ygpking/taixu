package top.wkbin.taixu.harness.projection

import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * 启动节流监听。**必须在专属协程内调用一次并保持挂起**——本函数会一直
     * collect 上游流直到该协程被取消；在 Application 里用 `launch { }` 启动，
     * 不要放在会返回的初始化路径里（否则节流只生效一瞬间）。
     *
     * 实现要点：这里刻意**不用** `core:ui-util` 的 `throttleLatest` 算子——
     * 那个算子会把每个上游状态聚合进 `List<T>` 批次再发射，对本场景意味着
     * 每 100ms 额外保留一份包含全部历史批次的列表引用，而这些中间列表随后
     * 只取 `lastOrNull()` 就被丢弃：纯属白付一次全量持有的内存成本。
     * 这里的语义只需要「最新值 + 时间窗口」，等价于 combine(conflate + 采样)，
     * 因此直接就地采样，不产生任何中间集合。
     *
     * 这样 Harness 层（150ms 一帧的全量内容快照）到 Compose 重组之间多了一层
     * 100ms 的采样保护，UI 永远不会因为上游抖动在每个 chunk 上重建整列。
     */
    fun startThrottling(
        throttleWindowMs: Long = 100,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            val pending = AtomicReference<List<HarnessMessage>?>(null)
            val flusher = launch {
                while (isActive) {
                    delay(throttleWindowMs)
                    pending.getAndSet(null)?.let { latest ->
                        _throttledForegroundMessages.value = latest
                    }
                }
            }
            try {
                projector.foregroundMessages.collect { latest ->
                    pending.set(latest)
                }
            } finally {
                flusher.cancel()
                // 收尾兜底：把最后一帧放出去，避免流结束后 UI 停在旧内容上
                pending.getAndSet(null)?.let { _throttledForegroundMessages.value = it }
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
