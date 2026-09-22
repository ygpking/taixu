package top.wkbin.taixu.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.model.ContextBudgetDefaults

/** Pure context-budget and historical-folding policy used by the provider mapper and UI. */
object ContextWindowPolicy {
    // Input budget reserves headroom for system prompt, tool/MCP schemas, completion
    // tokens and provider overhead instead of spending the whole model window on history.
    private const val MAX_SYSTEM_PROMPT_FRACTION = 0.60
    private const val MIN_SYSTEM_PROMPT_TOKENS = 512
    /**
     * 上下文预算的单一真相源（single source of truth）。
     *
     * 语义约定（用户可见、可预期）：
     *  - `declaredTokens` 是用户为该模型填写的「上下文上限」，即用户对模型实际能力的声明。
     *  - 系统唯一会做的是「预留」：给 completion 输出与工具 schema 腾出空间，
     *    这部分占用是协议的硬需求，与模型档位无关。
     *  - 系统**不再**设置任何与模型脱钩的固定硬帽（旧的 SAFE_GENERATION_CAP / MAX_CONTEXT_BUDGET=200000
     *    会把用户填的 100 万静默砍到 20 万再砍到 9.6 万，导致「面板显示 / 折叠决策」两张皮）。
     *
     * 折叠触发线由 [foldingLimitFor] 基于此预算减去预留后得出，保证「填多少、显示多少、按多少折叠」三处一致。
     */
    fun resolveEffectiveBudget(declaredTokens: Int?): Int {
        val declared = declaredTokens ?: DEFAULT_CONTEXT_BUDGET
        return declared.coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET)
    }

    /**
     * 解析「单次输入上限」= 每轮请求主动裁切到的目标水位，即**裁切基准**。
     *
     * 与 [resolveEffectiveBudget]（上下文窗口能力）严格区分，二者不可混用：
     *  - 窗口：回答「整个模型总共能装多大」，**不参与**裁切决策；
     *  - 输入上限：回答「我每轮主动裁到多少」，折叠触发线以此为准。
     *
     * 历史缺陷：裁切基准曾直接取窗口值。用户把窗口填成 1_000_000 后，折叠触发线
     * 随之升到 ~98.7 万，而实际输入峰值仅 38 万 → 永不折叠（BudgetContinuations=0）→ HTTP 413。
     *
     * 取值优先级：
     *  1. 模型档案 `inputTokenLimit`（显式配置）；
     *  2. 全局 `agent_input_token_limit`；
     *  3. 按窗口推导：`窗口 × [ContextBudgetDefaults.INPUT_LIMIT_WINDOW_RATIO_PERCENT]%`，
     *     且不超过 [ContextBudgetDefaults.DEFAULT_INPUT_LIMIT]。
     *
     * 规则 3 的兜底意义：输入上限随窗口线性放大（不再有与模型脱钩的固定硬帽），
     * 但始终被封在窗口之内——不会出现「窗口填多大、请求就堆多大」的失控。
     * 数值请以 [ContextBudgetDefaults.resolveInputLimit] 为准，注释不复述具体数字。
     */
    fun resolveInputLimit(
        declaredInputLimit: Int?,
        windowBudget: Int,
        globalInputLimit: Int? = null,
    ): Int {
        if (declaredInputLimit != null && declaredInputLimit > 0) {
            return ContextBudgetDefaults.normalizeInputLimit(declaredInputLimit)
        }
        if (globalInputLimit != null && globalInputLimit > 0) {
            return ContextBudgetDefaults.normalizeInputLimit(globalInputLimit)
        }
        return ContextBudgetDefaults.resolveInputLimit(null, windowBudget)
    }

    /**
     * 历史折叠触发线（token）。
     *
     * 公式：`min(预算 × 比例, 预算 − 协议预留)`，再夹到 [MIN_CONTEXT_BUDGET] 以上。
     *  - `ratioPercent = 100`（默认）时退化为「预算 − 预留」，与旧行为完全一致；
     *  - 调小比例可让历史更早折叠，降低单次请求 token 量。
     * 之所以取 min：比例只是「提前折叠」的手段，绝不能把折叠线推到超过「预算 − 预留」
     * —— 那会让历史挤占 completion 与工具 schema 的空间，导致上游 400 或生成崩塌。
     *
     * 面板必须显示本函数的结果（而非原始预算），使「填多少、看到多少、实际按多少折叠」三处一致。
     */
    fun foldingLimitFor(
        budget: Int,
        ratioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        systemTokens: Int = 0,
        reserveTokens: Int? = null,
    ): Int {
        if (budget <= 0) return 0
        // per-model 自定义输出预留优先（对齐 pi reserveTokens）；未提供时用内置预留。
        // 内置预留按预算比例封顶（见 reservedOutputTokens）：绝对值 8,192+4,096=12,288 在
        // 4K/8K 小窗口档位超过预算本身，会把折叠线压成 0、历史每轮塌到最小轮。
        val reserved = (reserveTokens ?: reservedOutputTokens(budget)) + toolSchemaReserveTokens(budget)
        val hardCeiling = budget - reserved
        val safeRatio = ratioPercent.coerceIn(MIN_FOLDING_RATIO_PERCENT, MAX_FOLDING_RATIO_PERCENT)
        val scaled = (budget.toLong() * safeRatio / 100L).toInt()
        // systemTokens 必须在「夹过 MIN_CONTEXT_BUDGET 下限」之后再扣，保持既有扣减顺序
        // 「比例水位 → 协议预留 → system 占用」：
        //  · budget=4_000、systemTokens=4_000（预算已被 system 吃光）时，本式得 4_000−4_000=0，
        //    下游据此只保留最小最近轮；
        //  · 若把 systemTokens 并进 hardCeiling 一起夹下限，MIN_CONTEXT_BUDGET 会把结果抬回 4_000，
        //    「预算耗尽」反而保留全部历史——ContextWindowPolicyTest / SessionModelSwitcherTest 会挂。
        val limited = minOf(scaled, hardCeiling).coerceAtLeast(MIN_CONTEXT_BUDGET)
        // 末尾夹 0 下限：systemTokens 是在「夹过 MIN_CONTEXT_BUDGET 之后」单独扣的
        // （上面的注释解释了为什么不能提前夹），小窗口模型下 limited − systemTokens
        // 可能为负。负值对引擎无害（computeKeepFromIndex 的 rawLimit <= 0 分支按
        // 「预算耗尽、只保留最小最近轮」处理），但会直接泄漏到 UI：设置页曾渲染出
        // 「每轮请求约在 -2K token 处自动压缩」。这里统一夹成 0，语义不变、显示正确。
        return (limited - systemTokens.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * 折叠线比例的默认值。真相源见 [ContextBudgetDefaults.DEFAULT_FOLDING_RATIO_PERCENT]。
     *
     * ⚠️ 不要在注释里复述具体数值：本文件曾把 85 写进三处 KDoc（本行、下方 [DEFAULT_FOLDING_RATIO_PERCENT]
     * 的说明、[computeKeepFromIndex] 的参数文档），而真相源已改为 90 —— 注释与代码漂移后
     * 会误导后续调参。**引用常量即可，让数值只有一处**。
     */
    const val DEFAULT_FOLDING_RATIO_PERCENT = ContextBudgetDefaults.DEFAULT_FOLDING_RATIO_PERCENT
    /** 折叠线比例下限：低于此值会频繁折叠，历史几乎留不住。 */
    const val MIN_FOLDING_RATIO_PERCENT = 10
    /** 折叠线比例上限。 */
    const val MAX_FOLDING_RATIO_PERCENT = 100

    /**
     * 折叠后保留窗口的 token 总量上限（默认 20,000，与 OMP 的 compaction.keepRecentTokens 对齐）。
     *
     * 为什么必须有：`MIN_KEEP_MESSAGES` 只约束「条数」，而单条 tool_result 的 token 量差异巨大
     * （实测单条可达 1.2 万 token）。若只有条数下限，折叠后仍可能留下十几万 token，
     * 下一轮请求依旧庞大 —— 表现为「压缩日志有了、token 却降不下来」。
     * 该值只作为条数下限之上的护栏：永远不会让保留窗口少于最后一条消息。
     */
    const val DEFAULT_MAX_KEEP_TOKENS = ContextBudgetDefaults.DEFAULT_MAX_KEEP_TOKENS

    /**
     * 预留：completion 输出空间（协议硬需求）。
     *
     * 但**不能是与模型档位无关的绝对值**：8,192 + 4,096 = 12,288 在 4K/8K 小窗口档位
     * 直接超过预算本身（8K 档预算 7,200），折叠线被 `coerceAtLeast(MIN_CONTEXT_BUDGET)`
     * 抬成 0 或负数后历史每轮塌到 2 条。改为按预算比例封顶：比例优先，绝对值只作下限兜底。
     */
    private const val RESERVED_OUTPUT_FRACTION = 0.15
    private const val TOOL_SCHEMA_RESERVE_FRACTION = 0.08
    /** 待处理状态（与 HarnessMessage.SKILL_SUGGESTION_PENDING 同源）。 */
    private const val PENDING_STATUS = SKILL_SUGGESTION_PENDING

    private const val RESERVED_OUTPUT_TOKENS = 8_192
    private const val TOOL_SCHEMA_RESERVE_TOKENS = 4_096

    /** 按预算比例封顶后的输出预留（小窗口档位不再吃掉整个预算）。 */
    internal fun reservedOutputTokens(budget: Int): Int =
        minOf(RESERVED_OUTPUT_TOKENS, (budget * RESERVED_OUTPUT_FRACTION).toInt().coerceAtLeast(512))

    /** 按预算比例封顶后的工具 schema 预留。 */
    internal fun toolSchemaReserveTokens(budget: Int): Int =
        minOf(TOOL_SCHEMA_RESERVE_TOKENS, (budget * TOOL_SCHEMA_RESERVE_FRACTION).toInt().coerceAtLeast(256))
    /** 兜底预算（模型未单独配置 contextTokens 且全局设置未生效时使用）。真相源见 [ContextBudgetDefaults]。 */
    const val DEFAULT_CONTEXT_BUDGET = ContextBudgetDefaults.DEFAULT_TOKENS
    /** 单次输入上限默认值（裁切基准兜底）。真相源见 [ContextBudgetDefaults]。 */
    const val DEFAULT_INPUT_LIMIT = ContextBudgetDefaults.DEFAULT_INPUT_LIMIT
    /** 单次输入上限下界。真相源见 [ContextBudgetDefaults]。 */
    const val MIN_INPUT_LIMIT = ContextBudgetDefaults.MIN_INPUT_LIMIT
    /** 预算下界：低于此值连系统提示词都放不下，属无效配置。真相源见 [ContextBudgetDefaults]。 */
    const val MIN_CONTEXT_BUDGET = ContextBudgetDefaults.MIN_TOKENS
    /** 预算上界：仅作为「明显异常输入」的护栏（如手误多打几个零），非模型能力限制。 */
    const val MAX_CONTEXT_BUDGET = ContextBudgetDefaults.MAX_TOKENS
    /**
     * 折叠时强制保留的最近消息条数下限（默认值）。
     * 取 10 条：一轮完整交互（用户提问 / 工具调用 / 工具结果 / 助手回复）通常 2~4 条，
     * 10 条可覆盖最近 3 轮左右，避免「只留 2 条」导致模型记不住前因。
     * 该下限受预算约束：小窗口模型会自动少保，但至少保住最近一轮。
     *
     * 用户可在「设置 → 压缩触发阈值（用户轮次）」覆盖该下限，见 [keepMessagesForRounds]。
     *
     * ⚠️ 2026-09 重构后：该「轮次阈值」设置已从引擎触发链路移除（触发只看 token，对齐 OMP），
     * 普通用户不再看到此参数；[keepMessagesForRounds] 保留仅供内部/测试调用，常量仍生效。
     *
     * ⚠️ 与 token 上限的关系（重要）：本常量只是「条数」维度的**下限**，
     * 最终保留窗口还要再过一道 [DEFAULT_MAX_KEEP_TOKENS] 的 token 上限护栏。
     * 当两者冲突时（例如保留 10 条就会超过 token 上限），**token 上限优先**——
     * 因为按条数保留无法约束体量：单条 tool_result 可达上万 token，
     * 硬保 10 条会让折叠后的请求依旧巨大（压缩了等于没压）。
     * 唯一不可牺牲的是「至少保住最后一条消息」，以保证 provider transcript 非空。
     */
    const val MIN_KEEP_MESSAGES = 10

    /** 一行「用户轮次」折算的消息条数（user + assistant 各一条的保守估计）。 */
    private const val MESSAGES_PER_ROUND = 2

    /**
     * 把「最少保留的用户轮数」换算成「最少保留的消息条数」。
     *
     * 设置项 `contextCompactionThreshold` 语义为轮次（5~40），引擎按消息条数工作，
     * 此处做唯一换算，避免两处各写一份口径（此前该设置完全未被引擎读取，属僵尸设置）。
     * 结果不低于 [MIN_KEEP_MESSAGES]，保证即使用户把轮次调到最小也不会退化成「只留 2 条」。
     */
    fun keepMessagesForRounds(rounds: Int?): Int {
        val safeRounds = rounds?.takeIf { it > 0 } ?: 0
        return (safeRounds * MESSAGES_PER_ROUND).coerceAtLeast(MIN_KEEP_MESSAGES)
    }
    private const val APPROX_CHARS_PER_TOKEN = 4

    /**
     * Compaction threshold (in characters) per tool type. `read`/`base` commonly
     * produce legitimately long output, so they get a higher bar; file mutations
     * and listings are compressed aggressively.
     */
    fun compactThresholdFor(toolName: String?): Int = when (toolName?.trim()?.lowercase()) {
        "read" -> 800
        "base", "download" -> 400
        "write", "edit" -> 200
        "process" -> 300
        else -> 240
    }

    /**
     * Tool-aware historical output compaction. Preserves the structurally important
     * parts of each tool family instead of applying one blind head/tail truncation.
     */
    fun compactToolOutput(toolName: String?, args: JsonObject?, output: String, success: Boolean): String {
        val statusLabel = if (success) "成功" else "失败"
        val header = "【历史执行结果·状态:$statusLabel】"
        val name = toolName?.trim()?.lowercase().orEmpty()
        val body = when (name) {
            "read" -> compactRead(args, output)
            "write", "edit" -> output.take(160) + "…[文件操作结果已压缩]"
            "process" -> compactList(output, 4, 2, "列表")
            "base", "download" -> compactCommand(output)
            else -> compactGeneric(output)
        }
        return "$header\n$body"
    }

    private fun compactRead(args: JsonObject?, output: String): String {
        val path = runCatching { args?.get("path")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val pathHint = path?.let { "（文件: $it）" }.orEmpty()
        val lines = output.lines()
        return if (lines.size > 12) {
            lines.take(6).joinToString("\n") +
                "\n... [历史 read 输出已压缩，省略 ${lines.size - 10} 行]$pathHint ...\n" +
                lines.takeLast(4).joinToString("\n")
        } else {
            output.take(600)
        }
    }

    private fun compactCommand(output: String): String {
        val lines = output.lines()
        return if (lines.size > 10) {
            lines.take(4).joinToString("\n") +
                "\n... [历史命令输出已压缩，省略 ${lines.size - 8} 行] ...\n" +
                lines.takeLast(4).joinToString("\n")
        } else {
            output.take(500)
        }
    }

    private fun compactList(output: String, head: Int, tail: Int, label: String): String {
        val lines = output.lines()
        return if (lines.size > head + tail + 2) {
            lines.take(head).joinToString("\n") +
                "\n... [$label 已压缩，省略 ${lines.size - head - tail} 项] ...\n" +
                lines.takeLast(tail).joinToString("\n")
        } else {
            output.take(300)
        }
    }

    private fun compactGeneric(output: String): String {
        val lines = output.lines()
        return if (lines.size > 6) {
            lines.take(3).joinToString("\n") +
                "\n... [历史工具输出已压缩，已略去 ${lines.size - 5} 行日志] ...\n" +
                lines.takeLast(2).joinToString("\n")
        } else {
            output.take(180) + "... [已自动压缩]"
        }
    }

    /**
     * 老轮次工具结果截断时原样保留的最近结果条数（下限兜底）。
     *
     * 主保护是轮次语义：最后一条用户消息之后的所有结果（活跃任务的全部工作轮，
     * 可达 maxToolsPerRound 条并行调用）一律原样。此下限只服务新用户消息到达后的
     * 「继续」式续跑——保留最近几条让模型知道当前状态；更早的结果压缩后头部仍
     * 保留关键状态（如浏览器快照 JSON 开头的 tab/url），全文可 history_read 回读。
     */
    const val KEEP_RECENT_TOOL_RESULTS = 4

    /**
     * 组装请求前的老轮次工具结果截断。
     *
     * 预算驱动的压缩（computeKeepFromIndex）只有越过 token 预算线才触发，
     * 在此之前每个历史轮的大输出（如浏览器快照、长 read）都会原样重复发送：
     * 一轮 2.7KB 的快照随对话增长累积，既烧 token 又稀释注意力。
     *
     * 策略：受保护 = 最后一条用户消息之后的全部结果（当前轮）∪ 最近
     * [keepRecentResults] 条结果（steering 兜底）；其余结果超过
     * [compactThresholdFor] 阈值时按 [compactToolOutput] 语义压缩，并附
     * history_read 指针。不改变消息条数与顺序（NATIVE 协议下丢消息会产生
     * 非法 transcript），只替换输出正文。
     *
     * @param toolCallDetails toolCallId → (工具名, 参数)，用于按工具类型选阈值与压缩形态
     * @return 变换后的消息列表；无命中时原样返回同一实例（避免无谓复制）
     */
    fun truncateStaleToolResults(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>>,
        keepRecentResults: Int = KEEP_RECENT_TOOL_RESULTS,
    ): List<HarnessMessage> {
        if (messages.isEmpty()) return messages
        val lastUserIndex = messages.indexOfLast { it is UserMessage }
        val protectedIds = messages.filterIsInstance<ToolResult>()
            .takeLast(keepRecentResults.coerceAtLeast(0))
            .mapTo(mutableSetOf()) { it.id }
        // 当前工作轮的结果（最后一条用户消息之后）全部保护：模型下一步决策就靠它们，
        // 一轮并行调用可达 maxToolsPerRound 条，固定条数覆盖不了。
        messages.forEachIndexed { index, message ->
            if (index > lastUserIndex && message is ToolResult) protectedIds.add(message.id)
        }
        var changed = false
        val transformed = messages.map { message ->
            if (message !is ToolResult || message.id in protectedIds) return@map message
            val (name, args) = toolCallDetails[message.toolCallId] ?: (null to null)
            if (message.output.length <= compactThresholdFor(name)) return@map message
            changed = true
            message.copy(
                output = compactToolOutput(name, args, message.output, message.success) +
                    "\n[老轮次工具输出已压缩；全文在会话记录中，需要细节时调用 history_read(message_id=\"${message.id}\") 回读]",
            )
        }
        return if (changed) transformed else messages
    }

    /**
     * 与引擎实际请求同口径的「投影」：把 UI 的全量消息投影成**真正会发送的那份**，
     * 供用量面板估算使用。内部复用 [truncateStaleToolResults]，并把工具名映射
     * （[HarnessApiMapper]）一并收敛，避免调用方各写一遍导致口径再次分化。
     *
     * 为何需要它：引擎在压缩判定前先截断老轮次工具结果（见 ApiContextAssembler），
     * 面板若直接拿未截断的全量消息估算，会明显虚高（实测 457.8K vs 实际发送 ~141K，约 3 倍）。
     *
     * @param compactionEnabled 关闭压缩时不做任何截断（用户要原始历史）。
     */
    fun projectForUsage(
        messages: List<HarnessMessage>,
        compactionEnabled: Boolean,
    ): List<HarnessMessage> {
        if (!compactionEnabled) return messages
        val toolCallDetails = messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }
        return truncateStaleToolResults(messages, toolCallDetails)
    }

    /** Conservative multilingual estimate used when a provider tokenizer is unavailable. */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var total = 0.0
        text.forEach { ch -> total += charTokenCost(ch) }
        return total.toInt().coerceAtLeast(1)
    }

    /**
     * 单个字符的 token 成本（与 [estimateTokens] 同一套权重）。
     *
     * 抽出来是为了让"切割"与"校验"共用同一把尺：此前 [fitSystemPrompt] 按
     * `APPROX_CHARS_PER_TOKEN=4` 反推字符数切割、再用本函数校验，两个口径差 1.6~2.22 倍，
     * 中文场景下这道后闸从未真正生效。
     */
    internal fun charTokenCost(ch: Char): Double = when {
        ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF -> 1.0 / 1.8
        ch.isWhitespace() -> 0.0
        ch.isLetterOrDigit() -> 1.0 / 2.5
        else -> 1.0 / 2.8
    }

    const val DEFAULT_SYSTEM_PROMPT_TOKENS = 576
    const val DEFAULT_NATIVE_TOOL_TOKENS = 3_600
    const val DEFAULT_RULES_TOKENS = 1_400
    const val DEFAULT_SUBAGENT_TOKENS = 1_100

    /** Session occupancy and compaction must share the same budget: current model, then the global fallback. */
    fun resolveBudget(profileContextTokens: Int?, defaultBudget: Int): Int =
        (profileContextTokens ?: defaultBudget).coerceAtLeast(1)

    /**
     * 会话占用判定（SessionModelSwitcher）与实际请求组装（ApiContextAssembler）共用的
     * 预算钳制：先按当前模型/全局回退取值，再统一钳制到 [1, MAX_CONTEXT_BUDGET]。
     * 两处必须走同一口径，否则会出现"切换模型判定无需压缩、实际请求又压缩"。
     */
    /**
     * 与 [resolveEffectiveBudget] 同一套下限（[MIN_CONTEXT_BUDGET]）。
     *
     * 原先这里夹的是 `1..MAX`，而引擎侧夹的是 `MIN_CONTEXT_BUDGET..MAX`——同一声明窗口值
     * 在两处解析出不同结果（declared=1000 时切换侧得 1,000、请求侧得 4,000）。
     * 当前因写入侧 normalize 已钳到 [MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET] 而不可达，
     * 属潜伏缺陷；统一口径后任何新写入路径都不会再触发分叉。
     */
    fun clampedBudget(profileContextTokens: Int?, defaultBudget: Int): Int =
        resolveBudget(profileContextTokens, defaultBudget).coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET)

    fun estimateReservedPromptTokens(
        pureChat: Boolean,
        toolDisabled: Boolean,
        skillTokens: Int = 0,
        mcpTokens: Int = 0,
        summaryTokens: Int = 0,
    ): Int {
        if (pureChat) return summaryTokens
        val toolTokens = if (toolDisabled) 0 else DEFAULT_NATIVE_TOOL_TOKENS + DEFAULT_SUBAGENT_TOKENS
        return DEFAULT_SYSTEM_PROMPT_TOKENS + DEFAULT_RULES_TOKENS + toolTokens + skillTokens + mcpTokens + summaryTokens
    }

    /** Estimate the payload after the same token-budget compaction used by [HarnessLoop]. */
    fun estimateEffectiveUsage(
        messages: List<HarnessMessage>,
        budget: Int,
        systemTokens: Int,
        compactionEnabled: Boolean,
        systemPromptTokens: Int = 0,
        toolDefinitionTokens: Int = 0,
        rulesTokens: Int = 0,
        skillsTokens: Int = 0,
        mcpTokens: Int = 0,
        subagentTokens: Int = 0,
        minKeepMessages: Int = MIN_KEEP_MESSAGES,
        foldingRatioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        maxKeepTokens: Int = DEFAULT_MAX_KEEP_TOKENS,
    ): EffectiveContextUsage {
        val keepFrom = if (compactionEnabled) {
            computeKeepFromIndex(messages, budget, systemTokens, minKeepMessages, foldingRatioPercent, maxKeepTokens)
        } else {
            0
        }
        val summarizedTokens = if (keepFrom > 0) {
            estimateTokens(buildHistorySummary(messages.take(keepFrom)))
        } else {
            0
        }
        var conversationTokens = 0
        var toolTokens = 0
        messages.drop(keepFrom).forEach { message ->
            when (message) {
                is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion -> Unit
                is UserMessage -> {
                    conversationTokens += estimateTokens(message.text) + message.imageUrls.size * 1_000
                }
                is AssistantText -> {
                    conversationTokens += estimateTokens(assistantTextForContext(message.text)) +
                        estimateTokens(message.reasoning.orEmpty())
                }
                is ToolCall -> {
                    toolTokens += estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
                }
                is ToolResult -> {
                    toolTokens += estimateTokens(message.output)
                }
            }
        }
        val resolvedSystemPromptTokens = if (systemPromptTokens == 0 && toolDefinitionTokens == 0 &&
            rulesTokens == 0 && skillsTokens == 0 && mcpTokens == 0 && subagentTokens == 0 && systemTokens > 0
        ) {
            systemTokens
        } else {
            systemPromptTokens
        }
        val breakdown = ContextUsageBreakdown(
            systemPromptTokens = resolvedSystemPromptTokens,
            toolDefinitionTokens = toolDefinitionTokens,
            rulesTokens = rulesTokens,
            skillsTokens = skillsTokens,
            mcpTokens = mcpTokens,
            subagentTokens = subagentTokens,
            summarizedTokens = summarizedTokens,
            conversationTokens = conversationTokens + toolTokens,
        )
        val computedTotal = if (breakdown.totalTokens > 0) {
            breakdown.totalTokens
        } else {
            systemTokens + conversationTokens + toolTokens + summarizedTokens
        }
        return EffectiveContextUsage(
            keepFromIndex = keepFrom,
            conversationTokens = conversationTokens,
            toolTokens = toolTokens,
            totalTokens = computedTotal,
            breakdown = breakdown,
        )
    }

    /**
     * 计算滑动窗口起点。
     *
     * 参数为两侧（本地 fork 与上游 v0.15.0）并集，缺省值即各自历史默认行为：
     * @param minKeepMessages 强制保留的最近消息条数下限。默认 [MIN_KEEP_MESSAGES]。
     *   2026-09 重构后调用方一律传常量（触发只看 token，不再由「用户轮次」设置驱动）。
     * @param foldingRatioPercent 折叠线比例（百分比，默认 [DEFAULT_FOLDING_RATIO_PERCENT]，
     *   数值以常量定义为准，此处不复述）。
     * @param maxKeepTokens 保留窗口的 token 总量上限（默认 [DEFAULT_MAX_KEEP_TOKENS]，参考 OMP
     *   的 keepRecentTokens=20000）。这是「条数下限」之上的第二道护栏：只按条数保留会失控
     *   （单条 tool_result 可达上万 token，10 条就可能留下十几万 token）。
     * @param keepRecentTokens 上游的「压缩触发后保留最近 N token」口径（对齐 pi，默认 0 = 不启用）。
     *   仅在压缩触发（预算边界 > 0）时生效：保留历史不超过该值，更早的进入摘要。
     * @param reserveTokens 为 LLM 响应预留的 token（对齐 pi reserveTokens）。null = 使用内置默认；
     *   每模型覆盖时替换全局保留值参与预算线计算。
     */
    fun computeKeepFromIndex(
        messages: List<HarnessMessage>,
        budget: Int,
        systemTokens: Int,
        minKeepMessages: Int = MIN_KEEP_MESSAGES,
        foldingRatioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
        maxKeepTokens: Int = DEFAULT_MAX_KEEP_TOKENS,
        keepRecentTokens: Int = 0,
        reserveTokens: Int? = null,
    ): Int {
        if (messages.size <= 1) return 0
        if (budget <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        // 折叠触发线：与 foldingLimitFor 同口径（budget × 水位，且不超过 budget − 协议预留 − system 占用）。
        // 曾额外叠加 `budget × 0.75` 的 upstreamLimit，导致折叠线被二次折上折压到 ~0.75×budget，
        // 大窗口被架空虚置、历史过早折叠（「记不住」根因之一）。现统一口径；
        // per-model 的 reserveTokens 仍生效（并入 foldingLimitFor 的预留计算）。
        // systemTokens 改由 foldingLimitFor 内部扣减，避免此处再减一次造成双重扣减。
        val localLimit = foldingLimitFor(
            budget = budget,
            ratioPercent = foldingRatioPercent,
            systemTokens = systemTokens,
            reserveTokens = reserveTokens,
        )
        val rawLimit = localLimit
        if (rawLimit <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        // 折叠触发线直接来自预算；不再叠加与模型脱钩的固定上限，
        // 使用户填写的「上下文上限」成为唯一决定因素（面板显示与之同源）。
        val limit = rawLimit
        var used = 0
        for (index in messages.indices.reversed()) {
            val tokens = messageTokens(messages[index])
            if (used + tokens > limit) {
                // 强制保留最近 minKeepMessages 条（即使已超 limit），避免「只留 2 条」导致
                // 模型记不住前因。上限受预算约束：小窗口模型自动少保，但至少保住最近一轮。
                //
                // ⚠️ 关键：条数下限必须再受「保留 token 上限」约束（参考 OMP 的 keepRecentTokens）。
                // 只按条数保留会失控——真实数据里单条 tool_result 可达 1.2 万 token，
                // MIN_KEEP_MESSAGES=10 条就可能留下十几万 token。
                val forcedFloor = ((messages.size - minKeepMessages).coerceAtLeast(0))
                var candidate = (index + 1).coerceIn(0, messages.lastIndex).coerceAtMost(forcedFloor)
                // 保留窗口 token 上限：以配置的 maxKeepTokens 为下限基数，但随预算动态放大到至少 budget/4，
                // 使大窗口（如 256K→预算 230K）不再被固定的 40K 上限卡住——历史保留与窗口同比例增长。
                val effectiveKeepCap = maxOf(maxKeepTokens, budget / 4)
                candidate = shrinkToTokenCap(messages, candidate, effectiveKeepCap)
                var boundary = alignKeepFromIndex(messages, candidate)
                // Split-turn（对齐 pi）：单个用户轮次自身超预算时，按用户轮次对齐会把
                // 整个巨型轮次保留下来，kept 仍超限，下一次请求必然溢出。
                // 此刻降级为轮内切割：切点回退到最近的合法边界（assistant 消息或工具调用），
                // 工具调用/结果配对由 closeToolPairs 保证不被拆散。
                // Split-turn 仅当边界已收敛到「最后一个用户轮次起点」时才有意义：此时
                // 整个窗口就是这一个超预算的轮次，轮内切割换回的是 token 收益。
                // 若边界跨多个轮次却仍超限，则预算上限本身过紧，此时保持轮次起点、宁可
                // 短暂略超限，也不能把窗口首条切到 assistant，破坏「窗口从 user 轮次开始」。
                val lastUserStart = messages.indexOfLast { it is UserMessage }
                if (boundary == lastUserStart && keptTokens(messages, boundary) > limit) {
                    val splitBoundary = alignSplitTurnBoundary(messages, candidate)
                    if (splitBoundary > 0) boundary = splitBoundary
                }
                return applyKeepRecentCap(messages, boundary, keepRecentTokens, limit)
            }
            used += tokens
        }
        return 0
    }

    /**
     * 在保留窗口的 token 总量超过 [maxKeepTokens] 时，从窗口最前端继续向内收缩，
     * 直到总量落回上限内（或只剩最后一条）。
     *
     * 语义：`keepFromIndex` 越大表示保留得越少。本函数只把该值继续推大（保留更少），
     * 不会反向保留更多，因此不会与「最少保留条数」的语义冲突——它是条数下限之上的
     * 第二道「token 上限」护栏。
     */
    private fun shrinkToTokenCap(
        messages: List<HarnessMessage>,
        keepFromIndex: Int,
        maxKeepTokens: Int,
    ): Int {
        if (maxKeepTokens <= 0 || keepFromIndex <= 0) return keepFromIndex
        var boundary = keepFromIndex.coerceIn(0, messages.lastIndex)
        // 从窗口末尾往前累计；一旦超上限，就把 boundary 推到该条之后。
        var used = 0
        for (index in messages.lastIndex downTo boundary) {
            val tokens = messageTokens(messages[index])
            if (used + tokens > maxKeepTokens) {
                // 至少保留最后一条：若连最后一条自身都超上限，则保留它（index == lastIndex 时）
                val next = (index + 1).coerceIn(boundary, messages.lastIndex)
                return next
            }
            used += tokens
        }
        return boundary
    }

    /**
     * 单条消息的 token 估算 —— **全类唯一的定义**。
     *
     * 此前存在一份逐字节相同的 `tokensOf`，两份都只服务于本文件的裁剪计算，
     * 注释还都写着"与另一处口径保持一致"——典型的"靠注释同步"，
     * 任一侧改权重另一侧就会静默分叉（同族教训：注释不会运行）。
     * 已合并为一份，重复定义不允许再加。压缩侧（CompactionManager）也走这里，
     * 不允许再出现第三套 `message.toString()` 粗估。
     */
    internal fun messageTokens(message: HarnessMessage): Int = when (message) {
        is CapabilityEvent, is ModelSwitchEvent, is SkillSuggestion -> 0
        is UserMessage -> estimateTokens(message.text) + message.imageUrls.size * 1_000
        is AssistantText -> estimateTokens(assistantTextForContext(message.text)) +
            estimateTokens(message.reasoning.orEmpty())
        is ToolResult -> estimateTokens(message.output)
        is ToolCall -> estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
    }

    /**
     * 压缩触发后的 keepRecentTokens 收紧（对齐 pi：压缩时保留最近 N token，更早的进摘要）。
     * 取预算边界与 keepRecent 边界中更激进者；收紧后若超出预算线则放弃收紧（预算优先）。
     */
    private fun applyKeepRecentCap(
        messages: List<HarnessMessage>,
        keepFrom: Int,
        keepRecentTokens: Int,
        limit: Int,
    ): Int {
        if (keepRecentTokens <= 0 || keepFrom <= 0) return keepFrom
        var used = 0
        for (index in messages.indices.reversed()) {
            used += messageTokens(messages[index])
            if (used > keepRecentTokens) {
                val keepRecentBoundary = alignSplitTurnBoundary(messages, index + 1).coerceAtLeast(1)
                val tightened = maxOf(keepRecentBoundary, keepFrom)
                return if (keptTokens(messages, tightened) > limit) keepFrom else tightened
            }
        }
        return keepFrom
    }

    private fun keptTokens(messages: List<HarnessMessage>, keepFrom: Int): Int =
        messages.drop(keepFrom).sumOf(::messageTokens)

    /**
     * Split-turn 轮内切割：从 [candidate] 向后回退到最近的合法切点。
     * 合法切点 = user / assistant / tool_call 消息（绝不切在 tool_result 上，
     * 否则结果与其调用分离，产生非法 transcript）。
     */
    private fun alignSplitTurnBoundary(messages: List<HarnessMessage>, candidate: Int): Int {
        var boundary = candidate.coerceIn(0, messages.lastIndex)
        while (boundary > 0 && messages[boundary] !is UserMessage &&
            messages[boundary] !is AssistantText && messages[boundary] !is ToolCall
        ) {
            boundary--
        }
        if (boundary <= 0) return 0
        return closeToolPairs(messages, boundary)
    }

    private fun minimalKeepFromIndex(messages: List<HarnessMessage>): Int {
        val lastUser = messages.indexOfLast { it is UserMessage }
        // 未处理的技能建议是"带待办动作"的消息：一旦被折进压缩区，它既进不了摘要
        // （CompactionSummarizer 按 UI-only 跳过）、又以 0 token 计账不被预算保护，
        // 于是从投影里静默消失——用户看到的卡片没了，DB 里还在，但没有任何入口能再看到它。
        // 把它并进最小保留单元，压缩边界最远只能切到它之前。
        val pendingSuggestion = messages.indexOfLast {
            it is SkillSuggestion && it.status == PENDING_STATUS
        }
        val candidate = when {
            pendingSuggestion >= 0 -> minOf(pendingSuggestion, if (lastUser >= 0) lastUser else messages.lastIndex)
                .coerceAtLeast(0)
            lastUser >= 0 -> lastUser
            messages.size > 1 -> messages.lastIndex
            else -> 0
        }
        val aligned = alignKeepFromIndex(messages, candidate)
        // A two-message tool pair (or the only user turn) cannot be shortened without
        // producing an invalid provider transcript. Preserve that minimal protocol unit.
        return if (aligned <= 0) 0 else aligned.coerceAtMost(messages.lastIndex)
    }

    /**
     * Prevent an oversized dynamic prompt from consuming the entire context before history.
     *
     * 切割必须与校验用同一把尺：这里按 [estimateTokens] 的字符权重（中文 1.8 / ASCII 2.5 /
     * 标点 2.8 字符每 token）累加推进到 [maxTokens]，而不是按 `APPROX_CHARS_PER_TOKEN=4`
     * 反推字符数。旧口径下 CJK 提示词"截断后"仍超上限 2.22 倍——因为 4 字符/token 的粗估
     * 与 1.8 字符/token 的实估差 2.22 倍，等于这道后闸在中文场景从未生效（实测 8K 档
     * 切割后 10,650 tokens > 整预算 8,000）。
     *
     * 切点按**码点**推进：`String.take` 按 char 切割会把 emoji 的代理对劈开，
     * 产出含孤立代理项的字符串，进 JSON 请求体有被上游 400 的风险。
     */
    fun fitSystemPrompt(prompt: String, budget: Int): String {
        if (prompt.isBlank() || budget <= 0) return prompt
        val maxTokens = (budget * MAX_SYSTEM_PROMPT_FRACTION).toInt().coerceAtLeast(MIN_SYSTEM_PROMPT_TOKENS)
        if (estimateTokens(prompt) <= maxTokens) return prompt
        val suffix = "\n\n[系统提示因上下文预算受限已截断；请优先遵守以上核心规则]"
        // 后缀也要占预算，否则"截断后"仍然超线。
        val targetTokens = (maxTokens - estimateTokens(suffix)).coerceAtLeast(1)
        var used = 0.0
        var end = 0
        while (end < prompt.length) {
            val cp = prompt.codePointAt(end)
            val width = if (Character.isSupplementaryCodePoint(cp)) 2 else 1
            val cost = charTokenCost(prompt[end])
            if (used + cost > targetTokens) break
            used += cost
            end += width
        }
        // 一字未删（极短提示但估算越线）时不要附加"已截断"后缀——那会让日志与请求自我欺骗。
        if (end >= prompt.length) return prompt
        return prompt.take(end) + suffix
    }

    /**
     * Move a token-derived cut to a complete user turn. Cutting at an arbitrary message
     * can split a parallel tool exchange (call1, call2, result1, result2), which produces
     * an invalid provider transcript and can make the model retry the missing tool forever.
     */
    private fun alignKeepFromIndex(messages: List<HarnessMessage>, candidate: Int): Int {
        if (messages.isEmpty()) return 0
        val boundedCandidate = candidate.coerceIn(0, messages.lastIndex)
        val nextUser = (boundedCandidate..messages.lastIndex).firstOrNull { messages[it] is UserMessage }
        val previousUser = (boundedCandidate downTo 0).firstOrNull { messages[it] is UserMessage }
        val boundary = nextUser ?: previousUser ?: boundedCandidate
        return closeToolPairs(messages, boundary)
    }

    /**
     * Keep a tool call/result exchange together: if a kept ToolResult's call sits before
     * the boundary, pull the boundary back to that call. Repeat because moving back can
     * reveal another result from the same parallel tool-call group.
     */
    private fun closeToolPairs(messages: List<HarnessMessage>, boundary: Int): Int {
        var closed = boundary.coerceIn(0, messages.size)
        // 先一次 O(n) 建立 toolCallId -> 最后一次调用下标的索引：每个 ToolResult
        // 都做全表 indexOfLast 是 O(n^2)，长会话在每次请求组装的热路径上放大。
        val callIndexById = HashMap<String, Int>(messages.size)
        messages.forEachIndexed { idx, msg ->
            if (msg is ToolCall) callIndexById[msg.id] = idx
        }
        do {
            val previousBoundary = closed
            messages.subList(closed, messages.size)
                .filterIsInstance<ToolResult>()
                .forEach { result ->
                    val callIndex = callIndexById[result.toolCallId] ?: -1
                    if (callIndex in 0 until closed) closed = callIndex
                }
        } while (closed < previousBoundary)
        return closed
    }

    /**
     * Generated image bytes stay in the persisted transcript for UI rendering, but must never be
     * counted as language tokens or echoed into a subsequent provider request.
     */
    fun assistantTextForContext(text: String): String {
        if (!text.contains("data:image/", ignoreCase = true) &&
            !text.contains("\"b64_json\"", ignoreCase = true)
        ) return text

        val out = StringBuilder(minOf(text.length, 4_096))
        var cursor = 0
        while (cursor < text.length) {
            val dataStart = text.indexOf("data:image/", cursor, ignoreCase = true)
            val jsonKeyStart = text.indexOf("\"b64_json\"", cursor, ignoreCase = true)
            val nextStart = listOf(dataStart, jsonKeyStart).filter { it >= 0 }.minOrNull()
            if (nextStart == null) {
                out.append(text, cursor, text.length)
                break
            }
            if (nextStart == dataStart) {
                val markdownStart = text.lastIndexOf("![", dataStart).takeIf { start ->
                    start >= cursor && text.indexOf("](", start).let { it in start until dataStart }
                }
                val htmlStart = text.lastIndexOf("<img", dataStart, ignoreCase = true).takeIf { it >= cursor }
                val mediaStart = listOfNotNull(markdownStart, htmlStart).maxOrNull() ?: dataStart
                out.append(text, cursor, mediaStart)
                out.append("[助手生成了一张图片；图片二进制已从模型上下文中省略]")
                val end = text.indexOfAny(charArrayOf(')', '"', '\'', '>', ' ', '\n', '\r', '\t'), dataStart)
                cursor = if (end >= 0) end + 1 else text.length
            } else {
                out.append(text, cursor, jsonKeyStart)
                out.append("\"b64_json\":\"[图片二进制已省略]\"")
                val colon = text.indexOf(':', jsonKeyStart + 10)
                val valueStart = if (colon >= 0) text.indexOf('"', colon + 1) else -1
                val valueEnd = if (valueStart >= 0) text.indexOf('"', valueStart + 1) else -1
                cursor = if (valueEnd >= 0) valueEnd + 1 else text.length
            }
        }
        return out.toString()
    }

    fun buildHistorySummary(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>> = messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        },
    ): String {
        if (messages.isEmpty()) return ""
        val firstRequest = messages.filterIsInstance<UserMessage>().firstOrNull()?.text
            ?.replace('\n', ' ')?.take(240)
        val recentRequests = messages.filterIsInstance<UserMessage>().takeLast(8)
            .map { it.text.replace('\n', ' ').take(320) }
        val toolStates = messages.filterIsInstance<ToolResult>().takeLast(24).map { result ->
            val name = toolCallDetails[result.toolCallId]?.first ?: "tool"
            val args = toolCallDetails[result.toolCallId]?.second
            val output = if (result.output.length > compactThresholdFor(name)) {
                compactToolOutput(name, args, result.output, result.success)
            } else {
                result.output
            }
            val command = args?.get("command")?.jsonPrimitive?.contentOrNull
                ?: args?.get("path")?.jsonPrimitive?.contentOrNull
            "$name:${if (result.success) "成功" else "失败"} " +
                command.orEmpty().take(480) + " " + output.replace('\n', ' ').take(720)
        }
        // 模型调用失败时 HarnessLoop 会写入 "❌ …" 文本，它是故障现场而非阶段结论，
        // 原样输出会误导后续轮次；真正的失败信息由「失败根因线索」字段承载。
        val lastAssistant = messages.filterIsInstance<AssistantText>().lastOrNull()?.text
            ?.let(::assistantTextForContext)
            ?.replace('\n', ' ')?.take(600)
            ?.takeUnless { text -> EXECUTION_FAILURE_MARKERS.any { marker -> text.startsWith(marker) } }
        val textMessages = messages.mapNotNull {
            when (it) {
                is UserMessage -> it.text
                is AssistantText -> assistantTextForContext(it.text)
                else -> null
            }
        }
        val constraints = textMessages.asSequence()
            .flatMap { it.lineSequence() }
            .filter { line -> CONSTRAINT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }
            .map { it.trim().replace(WHITESPACE_REGEX, " ").take(220) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeLast(16)
        val decisions = textMessages.asSequence()
            .flatMap { it.lineSequence() }
            .filter { line -> DECISION_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }
            .map { it.trim().replace(WHITESPACE_REGEX, " ").take(220) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeLast(16)
        val files = textMessages.asSequence()
            .flatMap { text -> FILE_PATH_REGEX.findAll(text.take(4000)).map { it.value.take(180) } }
            .distinct()
            .toList()
            .takeLast(24)
        val failures = messages.filterIsInstance<ToolResult>().filter { !it.success }
            .takeLast(12)
            .map { it.output.replace('\n', ' ').take(480) }
        // 原先是 recentRequests.takeLast(4)，与「近期用户要求」逐字重复，白占预算并挤掉真实细节。
        // 改为只取尾部尚未被任何助手输出/工具动作回应的用户消息——即真正待处理的事项。
        val unresolved = messages.asReversed()
            .takeWhile { it !is AssistantText && it !is ToolCall && it !is ToolResult }
            .filterIsInstance<UserMessage>()
            .map { it.text.replace('\n', ' ').take(320) }
            .distinct()
            .reversed()
            .takeLast(4)
        return buildString {
            appendLine("[早期历史摘要，共折叠 ${messages.size} 条消息]")
            firstRequest?.takeIf { it.isNotBlank() }?.let { appendLine("初始目标：$it") }
            if (toolStates.isNotEmpty()) appendLine("关键工具状态（新→旧）：${toolStates.asReversed().joinToString(" | ")}")
            if (failures.isNotEmpty()) appendLine("失败根因线索（新→旧）：${failures.asReversed().joinToString(" | ")}")
            if (constraints.isNotEmpty()) appendLine("用户硬约束：${constraints.joinToString(" | ")}")
            if (decisions.isNotEmpty()) appendLine("关键决定：${decisions.joinToString(" | ")}")
            if (files.isNotEmpty()) appendLine("涉及文件：${files.joinToString(" | ")}")
            if (recentRequests.isNotEmpty()) appendLine("近期用户要求（新→旧）：${recentRequests.asReversed().joinToString(" | ")}")
            if (unresolved.isNotEmpty()) appendLine("未解决事项：${unresolved.joinToString(" | ")}")
            lastAssistant?.takeIf { it.isNotBlank() }?.let { appendLine("最近阶段结论：$it") }
        }.let { summary ->
            // 超长时保头 + 保尾：头部是初始目标与硬约束，尾部是最新工具状态与阶段结论，
            // 只省略中段冗余；原先的整体 take 会把最新的阶段结论整段砍掉。
            if (summary.length <= MAX_INCREMENTAL_SUMMARY_CHARS) summary
            else {
                val headBudget = MAX_INCREMENTAL_SUMMARY_CHARS / 2
                val tailBudget = MAX_INCREMENTAL_SUMMARY_CHARS - headBudget - 32
                summary.take(headBudget) + "\n[…中段省略…]\n" + summary.takeLast(tailBudget)
            }
        }
    }

    /**
     * Detect dynamic content patterns in a system prompt that would invalidate KV prefix-cache
     * on every turn, losing the ~90% discount DeepSeek and similar providers offer on cached
     * tokens. Returns a list of human-readable descriptions of what was found, or an empty list
     * when the prompt appears cache-stable.
     *
     * Use this in debug builds or CI to audit system prompt construction:
     * ```kotlin
     * val issues = ContextWindowPolicy.detectDynamicSystemPromptPatterns(systemPrompt)
     * if (issues.isNotEmpty()) Log.w("CacheStability", "Dynamic system prompt: $issues")
     * ```
     *
     * The check is intentionally lightweight — it pattern-matches common injection forms.
     * A negative result does not guarantee cache stability; it only means no obvious dynamic
     * content was detected.
     */
    fun detectDynamicSystemPromptPatterns(prompt: String): List<String> {
        if (prompt.isBlank()) return emptyList()
        val issues = mutableListOf<String>()
        // ISO 8601 date: 2026-09-05 or 2026/09/05
        if (Regex("""\b20\d{2}[-/]\d{2}[-/]\d{2}\b""").containsMatchIn(prompt)) {
            issues += "ISO 日期（YYYY-MM-DD）"
        }
        // Time of day: 14:30 or 14:30:05
        if (Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""").containsMatchIn(prompt)) {
            issues += "时间（HH:MM）"
        }
        // Unix timestamp (10-13 digit integer, typically used as currentTimeMillis or epochSeconds)
        if (Regex("""\b1[6-9]\d{8}\b|\b17\d{8}\b|\b1[6-9]\d{11}\b|\b17\d{11}\b""")
                .containsMatchIn(prompt)) {
            issues += "UNIX 时间戳"
        }
        // Chinese date patterns like "2026年9月5日"
        if (Regex("""\d{4}年\d{1,2}月\d{1,2}日""").containsMatchIn(prompt)) {
            issues += "中文日期（YYYY年M月D日）"
        }
        // Battery/charge level patterns: "电量: 83%" or "battery: 83%"
        if (Regex("""(?:电量|battery)[^\n]{0,20}?\d{1,3}%""", RegexOption.IGNORE_CASE)
                .containsMatchIn(prompt)) {
            issues += "实时电量百分比"
        }
        return issues
    }

    /** 失败时写入助手消息的前缀，属于故障现场而非阶段结论，不能当作「最近阶段结论」注入。 */
    private val EXECUTION_FAILURE_MARKERS = listOf("\u274c 执行异常", "\u274c 模型调用失败", "\u274c ")
    private val CONSTRAINT_MARKERS = listOf("必须", "不得", "禁止", "不能", "严禁", "要求", "must", "never", "should not")
    private val DECISION_MARKERS = listOf("决定", "采用", "改为", "选择", "方案", "decision", "use", "采用")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val MAX_INCREMENTAL_SUMMARY_CHARS = 8_000
    private val FILE_PATH_REGEX = Regex("(?:/workspace|/sdcard|[A-Za-z]:[\\\\/])[^\\s,，。；;，)\\]]+")
}

/**
 * 上下文 Token 用量明细（8 维细分模型，用于高保真还原现代化 Agent 上下文可视化面板）。
 */
data class ContextUsageBreakdown(
    val systemPromptTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val rulesTokens: Int = 0,
    val skillsTokens: Int = 0,
    val mcpTokens: Int = 0,
    val subagentTokens: Int = 0,
    val summarizedTokens: Int = 0,
    val conversationTokens: Int = 0,
) {
    val totalTokens: Int
        get() = systemPromptTokens +
            toolDefinitionTokens +
            rulesTokens +
            skillsTokens +
            mcpTokens +
            subagentTokens +
            summarizedTokens +
            conversationTokens
}

data class EffectiveContextUsage(
    val keepFromIndex: Int,
    val conversationTokens: Int,
    val toolTokens: Int,
    val totalTokens: Int,
    val breakdown: ContextUsageBreakdown = ContextUsageBreakdown(),
)
