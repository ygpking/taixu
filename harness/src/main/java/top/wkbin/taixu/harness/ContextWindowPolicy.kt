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
    ): EffectiveContextUsage {
        val keepFrom = if (compactionEnabled) {
            computeKeepFromIndex(messages, budget, systemTokens, minKeepMessages, foldingRatioPercent)
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
                is CapabilityEvent -> Unit
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
     * @param minKeepMessages 强制保留的最近消息条数下限。默认 [MIN_KEEP_MESSAGES]；
     *   调用方可由用户设置「压缩触发阈值（用户轮次）」经 [keepMessagesForRounds] 换算后传入，
     *   使该设置真正影响折叠行为（此前引擎完全不读该设置，属僵尸设置）。
     * @param foldingRatioPercent 折叠线比例（百分比，默认 100 = 与旧行为一致）。
     *   由用户设置「折叠线比例」传入，使历史可在预算的一部分处就开始折叠，
     *   避免长会话长期以数十万 token 的请求运行。
     */
    fun computeKeepFromIndex(
        messages: List<HarnessMessage>,
        budget: Int,
        systemTokens: Int,
        minKeepMessages: Int = MIN_KEEP_MESSAGES,
        foldingRatioPercent: Int = DEFAULT_FOLDING_RATIO_PERCENT,
    ): Int {
        if (messages.size <= 1) return 0
        if (budget <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        val rawLimit = foldingLimitFor(budget, foldingRatioPercent) - systemTokens
        // 预算耗尽（rawLimit<=0）时只保留最小近轮。
        // 「防失忆」职责由 minKeepMessages 强制保留最近若干条 + alignKeepFromIndex 的工具对闭合共同覆盖。
        if (rawLimit <= 0) {
            return alignKeepFromIndex(messages, minimalKeepFromIndex(messages))
        }
        // 折叠触发线直接来自预算；不再叠加与模型脱钩的固定上限，
        // 使用户填写的「上下文上限」成为唯一决定因素（面板显示与之同源）。
        val limit = rawLimit
        var used = 0
        for (index in messages.indices.reversed()) {
            val tokens = when (val message = messages[index]) {
                is CapabilityEvent -> 0
                is UserMessage -> estimateTokens(message.text) + message.imageUrls.size * 1_000
                is AssistantText -> estimateTokens(assistantTextForContext(message.text)) +
                    estimateTokens(message.reasoning.orEmpty())
                is ToolResult -> estimateTokens(message.output)
                is ToolCall -> estimateTokens(message.args.toString()) + estimateTokens(message.reasoning.orEmpty())
            }
            if (used + tokens > limit) {
                // 强制保留最近 minKeepMessages 条（即使已超 limit），避免「只留 2 条」导致
                // 模型记不住前因。上限受预算约束：小窗口模型自动少保，但至少保住最近一轮。
                val forcedFloor = ((messages.size - minKeepMessages).coerceAtLeast(0))
                val candidate = (index + 1).coerceIn(0, messages.lastIndex).coerceAtMost(forcedFloor)
                val tokenBoundary = alignKeepFromIndex(messages, candidate)
                return tokenBoundary
            }
            used += tokens
        }
        return 0
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
        var boundary = nextUser ?: previousUser ?: boundedCandidate

        // Defensive closure for persisted/interrupted histories where a result may have
        // crossed a user boundary. Repeat because moving back can reveal another result
        // from the same parallel tool-call group.
        do {
            val previousBoundary = boundary
            messages.subList(boundary, messages.size)
                .filterIsInstance<ToolResult>()
                .forEach { result ->
                    val callIndex = messages.indexOfLast {
                        it is ToolCall && it.id == result.toolCallId
                    }
                    if (callIndex in 0 until boundary) boundary = callIndex
                }
        } while (boundary < previousBoundary)
        return boundary
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
