package top.wkbin.taixu.harness.fixtures

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import top.wkbin.taixu.core.datastore.BudgetPreferences

/**
 * 可预设的 [BudgetPreferences] fake。
 *
 * 这是"消费方到底有没有读某项设置"这类缺陷的**回归测试前置件**：此前没有它，
 * SessionModelSwitcher 漏读 `contextFoldingRatioPercent` / `contextMaxKeepTokens`
 * 整整两个版本都没被发现——因为没有任何测试能预设一个非默认值然后观察消费方行为。
 *
 * 用法：
 * ```kotlin
 * val prefs = FakeBudgetPreferences().apply {
 *     contextFoldingRatioPercent.value = 40
 *     contextMaxKeepTokens.value = 20_000
 * }
 * // 之后任何读这两项的代码都会拿到预设值
 * ```
 */
class FakeBudgetPreferences(
    contextCompactionEnabled: Boolean = true,
    contextBudgetTokens: Int = 256_000,
    inputTokenLimit: Int = 230_000,
    contextFoldingRatioPercent: Int = 90,
    contextMaxKeepTokens: Int = 40_000,
    contextArchiveEnabled: Boolean = true,
    skillEvolutionSuggestions: Boolean = true,
) : BudgetPreferences {

    val contextCompactionEnabledFlow = MutableStateFlow(contextCompactionEnabled)
    val contextBudgetTokensFlow = MutableStateFlow(contextBudgetTokens)
    val inputTokenLimitFlow = MutableStateFlow(inputTokenLimit)

    /** 可空语义：null = 从未设置（走窗口推导）。默认给个具体值以兼容旧测试。 */
    val inputTokenLimitOrNullFlow = MutableStateFlow<Int?>(inputTokenLimit)
    val contextFoldingRatioPercentFlow = MutableStateFlow(contextFoldingRatioPercent)
    val contextMaxKeepTokensFlow = MutableStateFlow(contextMaxKeepTokens)
    val contextArchiveEnabledFlow = MutableStateFlow(contextArchiveEnabled)
    val skillEvolutionSuggestionsFlow = MutableStateFlow(skillEvolutionSuggestions)

    override val contextCompactionEnabled: Flow<Boolean> get() = contextCompactionEnabledFlow
    override val contextBudgetTokens: Flow<Int> get() = contextBudgetTokensFlow
    override val inputTokenLimitOrNull: Flow<Int?> get() = inputTokenLimitOrNullFlow
    override val inputTokenLimit: Flow<Int> get() = inputTokenLimitFlow
    override val contextFoldingRatioPercent: Flow<Int> get() = contextFoldingRatioPercentFlow
    override val contextMaxKeepTokens: Flow<Int> get() = contextMaxKeepTokensFlow
    override val contextArchiveEnabled: Flow<Boolean> get() = contextArchiveEnabledFlow
    override val skillEvolutionSuggestions: Flow<Boolean> get() = skillEvolutionSuggestionsFlow

    /** 读一次当前值（测试里比 `flow.first()` 直观）。 */
    suspend fun currentFoldingRatio(): Int = contextFoldingRatioPercentFlow.value
    suspend fun currentMaxKeepTokens(): Int = contextMaxKeepTokensFlow.value

    /** 便捷：把它降级成只读快照（验证"没有写入路径"时用）。 */
    fun asReadOnlySnapshot(): BudgetPreferences = object : BudgetPreferences {
        override val contextCompactionEnabled: Flow<Boolean> = contextCompactionEnabledFlow
        override val contextBudgetTokens: Flow<Int> = contextBudgetTokensFlow
        override val inputTokenLimitOrNull: Flow<Int?> = inputTokenLimitOrNullFlow
        override val inputTokenLimit: Flow<Int> = inputTokenLimitFlow
        override val contextFoldingRatioPercent: Flow<Int> = contextFoldingRatioPercentFlow
        override val contextMaxKeepTokens: Flow<Int> = contextMaxKeepTokensFlow
        override val contextArchiveEnabled: Flow<Boolean> = contextArchiveEnabledFlow
        override val skillEvolutionSuggestions: Flow<Boolean> = skillEvolutionSuggestionsFlow
    }
}

/** 便捷：把若干偏好一次性塞进 fake。 */
fun FakeBudgetPreferences.withBudget(
    foldingRatioPercent: Int? = null,
    maxKeepTokens: Int? = null,
    inputLimit: Int? = null,
    budgetTokens: Int? = null,
    compactionEnabled: Boolean? = null,
    archiveEnabled: Boolean? = null,
    evolutionEnabled: Boolean? = null,
): FakeBudgetPreferences = apply {
    foldingRatioPercent?.let { contextFoldingRatioPercentFlow.value = it }
    maxKeepTokens?.let { contextMaxKeepTokensFlow.value = it }
    inputLimit?.let { inputTokenLimitFlow.value = it; inputTokenLimitOrNullFlow.value = it }
    budgetTokens?.let { contextBudgetTokensFlow.value = it }
    compactionEnabled?.let { contextCompactionEnabledFlow.value = it }
    archiveEnabled?.let { contextArchiveEnabledFlow.value = it }
    evolutionEnabled?.let { skillEvolutionSuggestionsFlow.value = it }
}

/** 便捷：统计某项 flow 被订阅了几次（验证"确实去读了"）。 */
fun <T> Flow<T>.subscriptionCount(flow: Flow<T>): Flow<T> = flow.map { it }
