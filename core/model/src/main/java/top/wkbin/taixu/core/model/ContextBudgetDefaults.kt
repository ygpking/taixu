package top.wkbin.taixu.core.model

/**
 * 上下文预算（contextTokens / 全局兜底预算）的**唯一真相源**。
 *
 * 为什么放在 `core:model`：预算这个数被四个层次同时使用，而它们之间**无法互相引用**——
 *  - `core:datastore`：读写全局兜底预算（`agent_context_budget_tokens`）时的默认值与钳制区间；
 *  - `harness`：引擎侧 `ContextWindowPolicy.resolveEffectiveBudget` 的兜底与钳制；
 *  - `tools`：模型档案写入侧 `AiProfileWriter.normalizeContextTokens` 的规范化区间；
 *  - `feature` 各模块：设置页/面板展示的初值。
 *
 * 其中 datastore、tools 都不依赖 harness（harness 依赖它们，反向引用会形成循环依赖），
 * 历史上只能在各自模块里**镜像定义**同一组数字，并靠注释互相提醒「必须保持同值」——
 * 这类「靠注释同步」的常量一旦漂移，就会出现「填多少 / 存多少 / 显示多少 / 按多少折叠」
 * 四处不一致（例如用户填 200000 被存成 128000，或面板显示 500K 而引擎按 96K 折叠）。
 *
 * `core:model` 位于依赖图底部，上述模块全部依赖它，因此把这组数字收敛到这里，
 * 由类型系统而非注释来保证一致。**新增同类常量请一律加在这里，不要各模块镜像。**
 */
object ContextBudgetDefaults {

    /**
     * 兜底预算：模型未单独配置 `contextTokens`、且全局设置未生效时使用。
     * 128_000 是主流大模型（GPT-4o / Claude 3.5+ / Qwen-Max 等）的常见窗口档位。
     */
    const val DEFAULT_TOKENS = 128_000

    /**
     * 预算下界：低于此值连系统提示词都放不下，属无效配置。
     * 同时用于写入侧钳制，防止存进无意义的极小值。
     */
    const val MIN_TOKENS = 4_000

    /**
     * 预算上界：仅作为「明显异常输入」的护栏（如手误多打几个零），
     * **不是**模型能力限制——真实可用窗口由模型档案的 `contextTokens` 表达。
     */
    const val MAX_TOKENS = 2_000_000

    /** 把任意输入规范化到 [MIN_TOKENS]..[MAX_TOKENS]，供写入侧与引擎侧共用。 */
    fun normalize(value: Int): Int = value.coerceIn(MIN_TOKENS, MAX_TOKENS)
}
