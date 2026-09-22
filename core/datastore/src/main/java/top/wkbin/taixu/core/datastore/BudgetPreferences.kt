package top.wkbin.taixu.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * 预算与技能进化相关的偏好**窄读接口**。
 *
 * 存在的原因：`AgentPreferences` 是非 open 的具体类、60+ 成员，测试无法廉价 fake，
 * 于是"某个消费方到底有没有读某项设置"这类缺陷根本写不出回归测试
 * （例如 SessionModelSwitcher 曾漏读折叠比例与保留上限，只能靠人工比对代码发现）。
 *
 * 预算解析（`ContextBudgetResolver`）与技能进化顾问只依赖这里声明的几项，
 * 测试用一个十行的 fake 就能预设任意组合、验证"读/没读"。
 */
interface BudgetPreferences {
    /** 上下文压缩总开关（默认开）。 */
    val contextCompactionEnabled: Flow<Boolean>

    /** 会话占用预算兜底值（模型未声明 contextTokens 时用）。 */
    val contextBudgetTokens: Flow<Int>

    /** 全局「单次输入上限」当前语义（含默认值）。 */
    val inputTokenLimit: Flow<Int>

    /** 折叠线比例百分比（10..100，默认 90）。 */
    val contextFoldingRatioPercent: Flow<Int>

    /** 保留窗口 token 上限。 */
    val contextMaxKeepTokens: Flow<Int>

    /** 原文归档开关。 */
    val contextArchiveEnabled: Flow<Boolean>

    /** 对话结束后自动建议沉淀/进化技能。 */
    val skillEvolutionSuggestions: Flow<Boolean>
}
