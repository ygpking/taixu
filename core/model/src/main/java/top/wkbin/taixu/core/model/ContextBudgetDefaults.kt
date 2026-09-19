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
     * 256_000 对齐主流大模型（GPT-4.1 / Claude 3.5+ / Gemini 1.5+ 等）的 200K+ 窗口，
     * 让「窗口」配置真正贯通到历史可用额度（实际裁切基准由 [resolveInputLimit] 从窗口推导）。
     * 128_000 → 256_000：原值仅为「常见档位」，实测会把 128K 窗口压到历史仅剩 ~23K，对话易失忆。
     */
    const val DEFAULT_TOKENS = 256_000

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

    // ---------------------------------------------------------------------
    // 「单次输入上限」——裁切基准的真相源
    //
    // 语义与 [DEFAULT_TOKENS]（上下文窗口能力声明）**完全不同**，二者必须分开：
    //   - 上下文窗口（contextTokens）回答「这个模型总共能装多大」；
    //   - 单次输入上限（inputTokenLimit）回答「我每轮主动裁到多少」。
    //
    // 历史缺陷：裁切基准曾直接取「上下文窗口」。用户把窗口填成 1_000_000 后，
    // 折叠触发线随之升到 ~98.7 万，历史堆到 38 万也不折叠 → 上游 HTTP 413。
    // 修法：裁切一律以「单次输入上限」为准，窗口大小只用于解析默认值与合理性校验。
    // ---------------------------------------------------------------------

    /**
     * 「单次输入上限」的默认值（用户或全局都未显式配置时）。
     * 230_000 ≈ [DEFAULT_TOKENS](256_000) × [INPUT_LIMIT_WINDOW_RATIO_PERCENT](90%)，
     * 与「窗口推导」结果对齐，保证默认窗口不被这道默认值架空（256K→历史可用约 200K）。
     * 128_000 → 230_000：旧值与默认窗口的一半对齐，会把 256K 窗口压回 128K 输入上限，与 M1 贯通目标冲突。
     */
    const val DEFAULT_INPUT_LIMIT = 230_000

    /** 输入上限下界：低于此值连系统提示词都放不下，属无效配置。 */
    const val MIN_INPUT_LIMIT = 4_000

    /** 输入上限上界：仅作为「明显异常输入」的护栏。 */
    const val MAX_INPUT_LIMIT = 2_000_000

    /**
     * 未显式配置输入上限时，按「上下文窗口 × 本比例」推导。
     * 90%：让窗口配置真正贯通到裁切基准（历史可用额度随窗口线性放大）。
     * 剩余 10% 与固定预留（输出 + 工具 schema）共同兜住协议开销，由 foldingLimitFor 统一扣减。
     * 50% → 90%：原 50% 叠加后续多道折上折后，128K 窗口的历史仅剩 ~23K，是「长对话失忆」的根因之一。
     */
    const val INPUT_LIMIT_WINDOW_RATIO_PERCENT = 90

    /**
     * 触发水位（裁切基准的百分比，默认 90）。历史在「裁切基准 × 本比例」处开始折叠。
     *
     * 与 [INPUT_LIMIT_WINDOW_RATIO_PERCENT] 的分工：
     *  - 前者决定「基准是多少」（输入上限，默认 12.8 万）；
     *  - 本值决定「基准用到百分之几才触发折叠」（90%，给输出与工具 schema 留 10% 余量；
     *    不足部分由 upstreamLimit 里的 RESERVED_OUTPUT_TOKENS + TOOL_SCHEMA_RESERVE 兜底）。
     * 二者相乘 = 实际触发线（约 11.5 万）。85→90：更晚触发折叠，让长对话保留更多原始历史、减少过早摘要。
     */
    const val DEFAULT_FOLDING_RATIO_PERCENT = 90

    /**
     * 压缩后保留窗口的 token 上限（默认 40000，参考 OMP `compaction.keepRecentTokens`，本地取更宽值）。
     * 防止「只按条数保留」时单条上万 token 的工具结果把保留窗口撑爆。
     * 20K→40K：给「长对话保留最近历史」更多余量，减少过早折导致的前文遗忘（预算护栏仍在，不会超限）。
     */
    const val DEFAULT_MAX_KEEP_TOKENS = 40_000

    /** 把任意输入上限规范化到 [MIN_INPUT_LIMIT]..[MAX_INPUT_LIMIT]。 */
    fun normalizeInputLimit(value: Int): Int = value.coerceIn(MIN_INPUT_LIMIT, MAX_INPUT_LIMIT)

    /**
     * 解析实际生效的「单次输入上限」。写入侧（模型档案/全局设置）与引擎侧共用，保证同源。
     *
     * 规则：
     *  1. 显式声明了（> 0）→ 规范化后直接采用；
     *  2. 未声明 → 取 `窗口 × [INPUT_LIMIT_WINDOW_RATIO_PERCENT]`，仅受 [MAX_INPUT_LIMIT] 护栏约束。
     *
     * 规则 2 的意义：窗口配置真正决定输入上限——用户把窗口填成 256K，输入上限即 ~230K，
     * 历史可用额度随之放大。**不再**用旧版 `min(窗口×50%, 128K)` 把大窗口架空虚置
     * （旧逻辑导致 256K 窗口实际只能用 ~128K，是「记不住」的根因）。
     * 输出/工具 schema 的协议开销由 foldingLimitFor 的固定预留统一兜底，无需在此砍比例。
     */
    fun resolveInputLimit(declared: Int?, windowTokens: Int?): Int {
        if (declared != null && declared > 0) return normalizeInputLimit(declared)
        val window = windowTokens?.takeIf { it > 0 } ?: DEFAULT_TOKENS
        val scaled = (window.toLong() * INPUT_LIMIT_WINDOW_RATIO_PERCENT / 100L).toInt()
        return normalizeInputLimit(scaled)
    }
}
