package top.wkbin.taixu.harness.budget

import top.wkbin.taixu.core.datastore.BudgetPreferences
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.model.ContextBudgetDefaults
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.ModelConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次请求的**预算解析结果**：所有消费方共用同一份数字，不再各自读设置、各自套公式。
 *
 * ## 为什么需要它
 * 此前有五个消费方各读各的设置、各算各的预算：
 *  · `ApiContextAssembler`（真实组装）——读全部设置，是事实上的口径基准；
 *  · `SessionModelSwitcher`（切换判定）——漏读 `contextFoldingRatioPercent` 与
 *    `contextMaxKeepTokens`，用户把比例设 100 时切换仍按硬编码 90% 折叠；
 *  · `ChatViewModel`（用量面板）——漏 per-model `compactionReserveTokens` /
 *    `compactionKeepRecentTokens`，且 systemTokens 用 catalog 常量而非真实提示词；
 *  · `SettingsViewModel` / `AgentSettingsScreen`（设置页预览）——用 catalog 估算；
 *  · `SubagentLaneRunner`（子智能体）——折叠比例/保留上限双缺，还用第三把 token 尺。
 *
 * 更严重的是**全局「单次输入上限」用具体默认值 230_000 表达"未配置"**，使
 * `ContextBudgetDefaults.resolveInputLimit` 的「窗口×90%」规则在全部调用点都是死代码。
 *
 * 本类把这些收敛到一处：读设置 → 套公式 → 产出数字。`ContextWindowPolicy` 继续做纯计算
 * （它是 object、其 `const val` 被编译期同源测试钉死，不打散），本类只负责"喂什么进去"。
 */
data class ResolvedBudget(
    /** 模型窗口能力（profile.contextTokens ?: 全局兜底），夹 [MIN, MAX]。 */
    val windowTokens: Int,
    /** 单次输入上限（裁切基准）：档案 > 全局（可空时按窗口推导） > 推导值。 */
    val inputLimit: Int,
    /** 实际用于折叠判定的预算 = min(inputLimit, window)。 */
    val budget: Int,
    /** 用户设置的折叠线比例百分比（10..100）。 */
    val foldingRatioPercent: Int,
    /** 用户设置的保留窗口 token 上限。 */
    val maxKeepTokens: Int,
    /** per-model 输出预留（null = 用内置按比例预留）。 */
    val reserveTokens: Int?,
    /** per-model 压缩后保留 token 收紧值（null = 不收紧）。 */
    val keepRecentTokens: Int?,
    /** 上下文压缩总开关。 */
    val compactionEnabled: Boolean,
    /** 原文归档开关。 */
    val archiveEnabled: Boolean,
) {
    companion object {
        /** 无偏好可用时的兜底（纯函数路径、测试、以及 DataStore 读取失败时）。 */
        fun fallback(model: ModelConfig): ResolvedBudget {
            val window = ContextWindowPolicy.resolveEffectiveBudget(model.contextTokens)
            return ResolvedBudget(
                windowTokens = window,
                inputLimit = minOf(window, ContextBudgetDefaults.DEFAULT_INPUT_LIMIT),
                budget = minOf(window, ContextBudgetDefaults.DEFAULT_INPUT_LIMIT),
                foldingRatioPercent = ContextBudgetDefaults.DEFAULT_FOLDING_RATIO_PERCENT,
                maxKeepTokens = ContextBudgetDefaults.DEFAULT_MAX_KEEP_TOKENS,
                reserveTokens = null,
                keepRecentTokens = null,
                compactionEnabled = true,
                archiveEnabled = true,
            )
        }
    }
}

/**
 * 预算解析器：**唯一**读预算相关设置的地方。
 *
 *  ResolveBudget` 是纯函数（无 IO），`resolve(model)` 是读偏好的挂起版本；
 * 测试用 [FakeBudgetPreferences] 预设任意组合即可验证"某个消费方到底有没有读某项设置"
 * ——这正是此前写不出回归测试的那类缺陷。
 */
@Singleton
class ContextBudgetResolver @Inject constructor(
    private val prefs: BudgetPreferences,
) {

    /** 读全部偏好并解析成一份预算。任何一步读取失败都退回 [ResolvedBudget.fallback]。 */
    suspend fun resolve(model: ModelConfig): ResolvedBudget =
        runCatching { resolveOrThrow(model) }.getOrElse { ResolvedBudget.fallback(model) }

    private suspend fun resolveOrThrow(model: ModelConfig): ResolvedBudget {
        val window = ContextWindowPolicy.resolveEffectiveBudget(model.contextTokens)
        val globalInputLimit = prefs.inputTokenLimitOrNull.first()
        // 规则 3（窗口×90%）在这里恢复生效：globalInputLimit 为 null 时走推导。
        val inputLimit = ContextWindowPolicy.resolveInputLimit(
            declaredInputLimit = model.inputTokenLimit,
            windowBudget = window,
            globalInputLimit = globalInputLimit,
        )
        return ResolvedBudget(
            windowTokens = window,
            inputLimit = inputLimit,
            budget = minOf(inputLimit, window),
            foldingRatioPercent = prefs.contextFoldingRatioPercent.first(),
            maxKeepTokens = prefs.contextMaxKeepTokens.first(),
            reserveTokens = model.compactionReserveTokens,
            keepRecentTokens = model.compactionKeepRecentTokens,
            compactionEnabled = prefs.contextCompactionEnabled.first(),
            archiveEnabled = prefs.contextArchiveEnabled.first(),
        )
    }

    /**
     * 纯函数版：不读偏好，全部入参由调用方给。用于设置页预览与单测
     * （预览要"假设用户把滑杆拖到 X"，本来就不该读当前值）。
     */
    fun resolveWith(
        model: ModelConfig,
        globalInputLimit: Int?,
        foldingRatioPercent: Int,
        maxKeepTokens: Int,
        compactionEnabled: Boolean = true,
        archiveEnabled: Boolean = true,
    ): ResolvedBudget {
        val window = ContextWindowPolicy.resolveEffectiveBudget(model.contextTokens)
        val inputLimit = ContextWindowPolicy.resolveInputLimit(
            declaredInputLimit = model.inputTokenLimit,
            windowBudget = window,
            globalInputLimit = globalInputLimit,
        )
        return ResolvedBudget(
            windowTokens = window,
            inputLimit = inputLimit,
            budget = minOf(inputLimit, window),
            foldingRatioPercent = foldingRatioPercent,
            maxKeepTokens = maxKeepTokens,
            reserveTokens = model.compactionReserveTokens,
            keepRecentTokens = model.compactionKeepRecentTokens,
            compactionEnabled = compactionEnabled,
            archiveEnabled = archiveEnabled,
        )
    }
}
