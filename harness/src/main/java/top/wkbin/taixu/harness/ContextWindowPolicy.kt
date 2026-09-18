package top.wkbin.taixu.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure context-budget and historical-folding policy used by the provider mapper and UI. */
object ContextWindowPolicy {
    // Input budget reserves headroom for system prompt, tool/MCP schemas, completion
    // tokens and provider overhead instead of spending the whole model window on history.
    private const val MAX_SYSTEM_PROMPT_FRACTION = 0.60
    private const val MIN_SYSTEM_PROMPT_TOKENS = 512
    /**
     * 输入预算占模型窗口的比例（上游 v0.15.0 口径，0.75）。
     * 折叠触发线同时取本地 [foldingLimitFor] 与 `budget × 本比例 − 预留` 两者中更严的一条，
     * 使不同窗口档位下都不会把历史挤到 completion/工具 schema 的空间里。
     */
    private const val INPUT_BUDGET_FRACTION = 0.75
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
    fun foldingLimitFor(budget: Int, ratioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT): Int {
        if (budget <= 0) return 0
        val reserved = RESERVED_OUTPUT_TOKENS + TOOL_SCHEMA_RESERVE_TOKENS
        val hardCeiling = budget - reserved
        val safeRatio = ratioPercent.coerceIn(MIN_FOLDING_RATIO_PERCENT, MAX_FOLDING_RATIO_PERCENT)
        val scaled = (budget.toLong() * safeRatio / 100L).toInt()
        return minOf(scaled, hardCeiling).coerceAtLeast(MIN_CONTEXT_BUDGET)
    }

    /** 折叠线比例的默认值（100 = 只在「预算 − 预留」处折叠，与旧行为一致）。 */
    const val DEFAULT_FOLDING_RATIO_PERCENT = 100
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
    const val DEFAULT_MAX_KEEP_TOKENS = 20_000

    /** 预留：completion 输出空间（协议硬需求，与模型档位无关）。 */
    private const val RESERVED_OUTPUT_TOKENS = 8_192
    /** 预留：工具/MCP schema 空间（协议硬需求，与模型档位无关）。 */
    private const val TOOL_SCHEMA_RESERVE_TOKENS = 4_096
    /** 兜底预算（模型未单独配置 contextTokens 且全局设置未生效时使用）。 */
    const val DEFAULT_CONTEXT_BUDGET = 128_000
    /** 预算下界：低于此值连系统提示词都放不下，属无效配置。 */
    const val MIN_CONTEXT_BUDGET = 4_000
    /** 预算上界：仅作为「明显异常输入」的护栏（如手误多打几个零），非模型能力限制。 */
    const val MAX_CONTEXT_BUDGET = 2_000_000
    /**
     * 折叠时强制保留的最近消息条数下限（默认值）。
     * 取 10 条：一轮完整交互（用户提问 / 工具调用 / 工具结果 / 助手回复）通常 2~4 条，
     * 10 条可覆盖最近 3 轮左右，避免「只留 2 条」导致模型记不住前因。
     * 该下限受预算约束：小窗口模型会自动少保，但至少保住最近一轮。
     *
     * 用户可在「设置 → 压缩触发阈值（用户轮次）」覆盖该下限，见 [keepMessagesForRounds]。
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
    /**
     * 历史消息占用的绝对安全上限（token）。无论模型标称窗口多高，压缩触发线都不超过此值，
     * 避免 flash 级模型在超高 token 下参数生成崩塌（上游 v0.15.0 引入的护栏）。
     * 与本地 [foldingLimitFor] 的预算护栏取更严者生效，两者并存不互斥。
     */
    const val SAFE_GENERATION_CAP = 96_000
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
        var cjk = 0
        var ascii = 0
        var punctuation = 0
        text.forEach { ch ->
            when {
                ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF -> cjk++
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> ascii++
                else -> punctuation++
            }
        }
        return (cjk / 1.8f + ascii / 2.5f + punctuation / 2.8f).toInt().coerceAtLeast(1)
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
    fun clampedBudget(profileContextTokens: Int?, defaultBudget: Int): Int =
        resolveBudget(profileContextTokens, defaultBudget).coerceIn(1, MAX_CONTEXT_BUDGET)

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
                is CapabilityEvent, is ModelSwitchEvent -> Unit
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
     * @param minKeepMessages 强制保留的最近消息条数下限。默认 [MIN_KEEP_MESSAGES]；
     *   调用方可由用户设置「压缩触发阈值（用户轮次）」经 [keepMessagesForRounds] 换算后传入。
     * @param foldingRatioPercent 折叠线比例（百分比，默认 100 = 与旧行为一致）。
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
        // 折叠触发线同时受本地预算护栏与上游输入预算线约束，取更严者（见 [foldingLimitFor]）。
        val localLimit = foldingLimitFor(budget, foldingRatioPercent) - systemTokens
        val upstreamLimit = (budget * INPUT_BUDGET_FRACTION).toInt() -
            systemTokens - (reserveTokens ?: RESERVED_OUTPUT_TOKENS) - TOOL_SCHEMA_RESERVE_TOKENS
        // rawLimit<=0 时只保留最小近轮：「防失忆」由 minKeepMessages 强制保留最近若干条
        // + alignKeepFromIndex 的工具对闭合共同覆盖。
        val rawLimit = minOf(localLimit, upstreamLimit)
        if (rawLimit <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        // 折叠触发线直接来自预算；不再叠加与模型脱钩的固定上限，
        // 使用户填写的「上下文上限」成为唯一决定因素（面板显示与之同源）。
        val limit = rawLimit
        var used = 0
        for (index in messages.indices.reversed()) {
            val tokens = tokensOf(messages[index])
            if (used + tokens > limit) {
                // 强制保留最近 minKeepMessages 条（即使已超 limit），避免「只留 2 条」导致
                // 模型记不住前因。上限受预算约束：小窗口模型自动少保，但至少保住最近一轮。
                //
                // ⚠️ 关键：条数下限必须再受「保留 token 上限」约束（参考 OMP 的 keepRecentTokens）。
                // 只按条数保留会失控——真实数据里单条 tool_result 可达 1.2 万 token，
                // MIN_KEEP_MESSAGES=10 条就可能留下十几万 token。
                val forcedFloor = ((messages.size - minKeepMessages).coerceAtLeast(0))
                var candidate = (index + 1).coerceIn(0, messages.lastIndex).coerceAtMost(forcedFloor)
                candidate = shrinkToTokenCap(messages, candidate, maxKeepTokens)
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

    /** 单条消息的 token 估算（与 computeKeepFromIndex 内的口径保持一致）。 */
    private fun messageTokens(message: HarnessMessage): Int = when (message) {
        is CapabilityEvent, is ModelSwitchEvent -> 0
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
            used += tokensOf(messages[index])
            if (used > keepRecentTokens) {
                val keepRecentBoundary = alignSplitTurnBoundary(messages, index + 1).coerceAtLeast(1)
                val tightened = maxOf(keepRecentBoundary, keepFrom)
                return if (keptTokens(messages, tightened) > limit) keepFrom else tightened
            }
        }
        return keepFrom
    }

    private fun tokensOf(message: HarnessMessage): Int = when (message) {
        is CapabilityEvent, is ModelSwitchEvent -> 0
        is UserMessage -> estimateTokens(message.text) + message.imageUrls.size * 1_000
        is AssistantText -> estimateTokens(assistantTextForContext(message.text)) +
            estimateTokens(message.reasoning.orEmpty())
        is ToolResult -> estimateTokens(message.output)
        is ToolCall -> estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
    }

    private fun keptTokens(messages: List<HarnessMessage>, keepFrom: Int): Int =
        messages.drop(keepFrom).sumOf(::tokensOf)

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
        val candidate = when {
            lastUser >= 0 -> lastUser
            messages.size > 1 -> messages.lastIndex
            else -> 0
        }
        val aligned = alignKeepFromIndex(messages, candidate)
        // A two-message tool pair (or the only user turn) cannot be shortened without
        // producing an invalid provider transcript. Preserve that minimal protocol unit.
        return if (aligned <= 0) 0 else aligned.coerceAtMost(messages.lastIndex)
    }

    /** Prevent an oversized dynamic prompt from consuming the entire context before history. */
    fun fitSystemPrompt(prompt: String, budget: Int): String {
        if (prompt.isBlank() || budget <= 0) return prompt
        val maxTokens = (budget * MAX_SYSTEM_PROMPT_FRACTION).toInt().coerceAtLeast(MIN_SYSTEM_PROMPT_TOKENS)
        if (estimateTokens(prompt) <= maxTokens) return prompt
        val suffix = "\n\n[系统提示因上下文预算受限已截断；请优先遵守以上核心规则]"
        return prompt.take((maxTokens * APPROX_CHARS_PER_TOKEN - suffix.length).coerceAtLeast(0)) + suffix
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
        do {
            val previousBoundary = closed
            messages.subList(closed, messages.size)
                .filterIsInstance<ToolResult>()
                .forEach { result ->
                    val callIndex = messages.indexOfLast {
                        it is ToolCall && it.id == result.toolCallId
                    }
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
