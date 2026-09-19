package top.wkbin.taixu.harness.session

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.ApiFunctionCall
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ApiToolCall
import top.wkbin.taixu.harness.MentionExtractor
import top.wkbin.taixu.harness.TextToolCallCodec
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder

/**
 * API 请求上下文组装器：把会话实时消息投影成提供商协议消息列表。
 *
 * 从原 HarnessLoop.apiMessages 迁移而来，负责：
 * - 系统提示词注入（非纯净聊天模式）
 * - 上下文压缩摘要的头部注入（预算驱动的滑动窗口折叠）
 * - NATIVE / JSON_TEXT 两种工具调用协议的消息形态转换
 * - 视觉能力关闭时剥离图片输入
 */
class ApiContextAssembler @Inject constructor(
    private val compactionManager: CompactionManager,
    private val settingsDataStore: AgentPreferences,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    suspend fun assemble(
        sessId: String,
        model: ModelConfig,
        workspacePath: String,
        projectTypeOverride: String = "",
        thinkingMode: Boolean = false,
    ): List<ApiMessage> {
        val compactionEnabled = runCatching { settingsDataStore.contextCompactionEnabled.first() }.getOrDefault(true)
        // 压缩/截断前是否把原文落盘（OMP 范式）——失败只降级为「纯摘要」，不中断压缩。
        val archiveEnabled = runCatching { settingsDataStore.contextArchiveEnabled.first() }.getOrDefault(true)
        // 与 SessionModelSwitcher/clampedBudget、ChatViewModel 面板同源：占用判定、面板显示、
        // 实际请求组装必须走同一预算口径，否则会出现「显示 500K、实际按 96K 折叠」两张皮。
        // 预算来源：模型单独配置优先，否则全局设置，再兜底 DEFAULT_CONTEXT_BUDGET。
        val declaredTokens = model.contextTokens
            ?: runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(ContextWindowPolicy.DEFAULT_CONTEXT_BUDGET)
        // 窗口能力：回答「总共能装多大」。只用于系统提示词容量上限与合理性校验，不参与裁切。
        val windowBudget = ContextWindowPolicy.resolveEffectiveBudget(declaredTokens)
        // 裁切基准：回答「每轮主动裁到多少」。优先级 = 模型档案 inputTokenLimit > 全局 inputTokenLimit > 按窗口推导。
        // 历史缺陷：此处曾直接用 resolveEffectiveBudget(窗口) 当裁切基准，用户填 100 万后折叠线升到 ~98.7 万，
        // 输入峰值 38 万永不越线 → BudgetContinuations=0 → HTTP 413 频发。
        val globalInputLimit = runCatching { settingsDataStore.inputTokenLimit.first() }.getOrNull()
        val inputLimit = ContextWindowPolicy.resolveInputLimit(model.inputTokenLimit, windowBudget, globalInputLimit)
        // 输入上限不得超过窗口本身（窗口更小时以窗口为准，避免把请求堆过物理上限）。
        val budgetTokens = minOf(inputLimit, windowBudget)
        // 保留条数下限固定为 MIN_KEEP_MESSAGES（约最近 3 轮），不再由「用户轮次」设置驱动。
        // 归因：旧设置 `contextCompactionThreshold` 是横向的「另一种计量单位」，与 token 水位并列
        // 会让用户无从判断该调哪个；OMP 的触发判定只看 token（compaction.ts thresholdPercent）。
        // 该 key 在数据层保留（不炸老配置），但引擎不再消费，触发完全由下面的 token 水位决定。
        val minKeepMessages = ContextWindowPolicy.MIN_KEEP_MESSAGES
        // 「折叠线比例」：让历史在预算的一部分处就开始折叠。
        // 与面板同源读取同一个偏好，保证两侧折叠决策一致。
        val foldingRatioPercent = runCatching { settingsDataStore.contextFoldingRatioPercent.first() }
            .getOrDefault(ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT)
        // 「保留窗口 token 上限」：只按条数保留会失控（单条 tool_result 可达上万 token），
        // 该值作为条数下限之上的护栏，把折叠后剩余窗口的 token 总量夹住。
        val maxKeepTokens = runCatching { settingsDataStore.contextMaxKeepTokens.first() }
            .getOrDefault(ContextWindowPolicy.DEFAULT_MAX_KEEP_TOKENS)
        val toolCallMode = if (model.pureChatMode) ToolCallMode.DISABLED else model.toolCallMode

        var compactedContext = compactionManager.project(sessId)
        var msgs = compactedContext.messages
        val latestUserText = msgs.filterIsInstance<UserMessage>().lastOrNull()?.text.orEmpty()
        val mentionedNames = MentionExtractor.parse(latestUserText)

        val rawSystemPrompt = if (!model.pureChatMode) {
            systemPromptBuilder.build(
                workspacePath,
                toolCallMode,
                mentionedNames,
                sessId,
                projectTypeOverride,
                latestUserText,
                mcpTools = model.dynamicMcpTools,
            )
        } else {
            ""
        }
        val systemPrompt = ContextWindowPolicy.fitSystemPrompt(rawSystemPrompt, budgetTokens)
        return buildList {
            if (systemPrompt.isNotEmpty()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }
            val answeredIds = msgs.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
            val toolCallDetails = msgs.filterIsInstance<ToolCall>().associate {
                it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
            }

            // 老轮次工具结果截断（先于压缩判定）：预算线未越过时，历史轮的大输出（浏览器快照、
            // 长 read）仍会原样重复发送。最近若干条原样保留，更老的超过按工具阈值即压缩并附
            // history_read 指针——只影响发给 Provider 的正文，落库 transcript 与 UI 不变。
            // 关闭上下文压缩 = 用户要原始历史，此时同样不截断。
            // 顺序必须在压缩判定之前：只需截断即可回到预算线内的会话，不应再触发整段压缩
            // （一次额外 LLM 调用 + 历史永久降级为摘要）。
            if (compactionEnabled) {
                msgs = ContextWindowPolicy.truncateStaleToolResults(msgs, toolCallDetails)
            }

            // 预算驱动的滑动窗口：从最近一轮往回累加 token，超出预算则更早的历史进入压缩态。
            // 是否裁剪原文只由真实 token 预算决定，不再按用户轮次阈值强制折叠。
            // 每模型压缩预算覆盖（pi 式 modelOverrides）：keepRecent 收紧 + reserve 预留。
            val computedKeepFromIndex = if (compactionEnabled) {
                ContextWindowPolicy.computeKeepFromIndex(
                    msgs,
                    budgetTokens,
                    ContextWindowPolicy.estimateTokens(systemPrompt) +
                        ContextWindowPolicy.estimateTokens(compactedContext.summaryLayer),
                    minKeepMessages = minKeepMessages,
                    foldingRatioPercent = foldingRatioPercent,
                    maxKeepTokens = maxKeepTokens,
                    keepRecentTokens = model.compactionKeepRecentTokens ?: 0,
                    reserveTokens = model.compactionReserveTokens,
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                // LLM 结构化压缩摘要（pi 式）：当前模型生成，失败回退机械摘要
                compactedContext = compactionManager.compact(
                    sessId,
                    compactedContext,
                    computedKeepFromIndex,
                    model = model,
                    archiveEnabled = archiveEnabled,
                    workspacePath = workspacePath,
                )
                msgs = compactedContext.messages
                // compact 返回的保留窗口来自原始 transcript（未截断），重放一次截断，
                // 保证与压缩判定时同一口径。
                if (compactionEnabled) {
                    msgs = ContextWindowPolicy.truncateStaleToolResults(msgs, toolCallDetails)
                }
            }
            val summaryLayer = compactedContext.summaryLayer
            if (summaryLayer.isNotBlank()) {
                add(
                    ApiMessage(
                        role = "system",
                        content = summaryLayer,
                    ),
                )
            }

            // JSON 文本模式：工具调用以文本表达，tool 消息需转成 user 文本（API 不认识 tool 角色）
            val toolNames = toolCallDetails.mapValuesTo(mutableMapOf()) { it.value.first }

            var i = 0
            fun apiToolCall(tc: ToolCall) = ApiToolCall(
                id = tc.id,
                function = ApiFunctionCall(
                    name = tc.rawToolName ?: HarnessApiMapper.apiName(tc.tool),
                    arguments = tc.args.toString(),
                ),
            )
            while (i < msgs.size) {
                val message = msgs[i]
                if (message is CapabilityEvent || message is ModelSwitchEvent) {
                    i++
                    continue
                }
                if (toolCallMode == ToolCallMode.JSON_TEXT) {
                    when (message) {
                        is ToolCall -> {
                            toolNames[message.id] = message.rawToolName ?: HarnessApiMapper.apiName(message.tool)
                            // 回放调用意图：落库的 assistant 文本已剥离工具标记，跳过会让模型
                            // 看不到自己上一轮调用了什么参数，结果无法与调用关联，易重复调用。
                            // 与 NATIVE 分支同口径：无结果的悬空调用不回放。
                            if (message.id in answeredIds) {
                                add(
                                    ApiMessage(
                                        role = "assistant",
                                        content = TextToolCallCodec.encodeCall(
                                            message.rawToolName ?: HarnessApiMapper.apiName(message.tool),
                                            message.args.toString(),
                                        ),
                                    ),
                                )
                            }
                            i++
                        }
                        is ToolResult -> {
                            val name = toolNames[message.toolCallId] ?: "工具"
                            val status = if (message.success) "成功" else "失败"
                            val content = "【工具 $name 执行结果·$status】\n${message.output}"
                            add(ApiMessage(role = "user", content = content))
                            i++
                        }
                        else -> {
                            val mapped = when (message) {
                                is AssistantText ->
                                    HarnessApiMapper.toApiMessage(message).copy(
                                        content = ContextWindowPolicy.assistantTextForContext(
                                            ProviderClient.stripThinkTags(message.text) ?: message.text,
                                        ),
                                        reasoning_content = null,
                                    )
                                else -> HarnessApiMapper.toApiMessage(message)
                            }
                            add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                            i++
                        }
                    }
                    continue
                }
                if (message is AssistantText || message is ToolCall) {
                    if (message is ToolCall && message.id !in answeredIds) {
                        i++
                        continue
                    }
                    val text = (message as? AssistantText)?.text?.let {
                        ContextWindowPolicy.assistantTextForContext(ProviderClient.stripThinkTags(it) ?: it)
                    }
                    val toolCalls = mutableListOf<ApiToolCall>()
                    if (message is ToolCall) toolCalls.add(apiToolCall(message))
                    var j = i + 1
                    while (j < msgs.size && msgs[j] is ToolCall) {
                        val tc = msgs[j] as ToolCall
                        if (tc.id in answeredIds) toolCalls.add(apiToolCall(tc))
                        j++
                    }
                    // DeepSeek 思考模式（V3.2+/V4）：两个 user 消息之间若有工具调用，
                    // 中间 assistant 消息的 reasoning_content 必须原样传回，否则
                    // 400 "The reasoning_content in the thinking mode must be passed back to the API"。
                    // 纯文本 assistant 轮仍不回传（DeepSeek-R1 规则，防推理循环）。
                    val reasoning = if (toolCalls.isNotEmpty()) {
                        (message as? AssistantText)?.reasoning
                            ?: (message as? ToolCall)?.reasoning
                            ?: msgs.subList(i, j).filterIsInstance<ToolCall>().firstNotNullOfOrNull { it.reasoning }
                    } else {
                        null
                    }
                    add(
                        ApiMessage(
                            role = "assistant",
                            content = text,
                            reasoning_content = reasoning,
                            tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                        ),
                    )
                    i = j
                } else if (message is ToolResult) {
                    val content = message.output
                    add(
                        ApiMessage(
                            role = "tool",
                            content = content,
                            tool_call_id = message.toolCallId,
                        ),
                    )
                    i++
                } else {
                    val mapped = HarnessApiMapper.toApiMessage(message)
                    add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                    i++
                }
            }
        }
    }

}
