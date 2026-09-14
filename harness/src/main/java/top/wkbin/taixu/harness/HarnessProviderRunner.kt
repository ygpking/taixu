package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.HarnessSessionEntity
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import top.wkbin.taixu.harness.metrics.RunMetrics
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.effects.RetryPolicy
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.events.AgentEventLogger
import top.wkbin.taixu.harness.events.CapabilityEventWriter
import top.wkbin.taixu.harness.projection.SessionMessageProjector
import top.wkbin.taixu.harness.projection.SessionStateMirrors
import top.wkbin.taixu.harness.session.ApiContextAssembler
import kotlin.time.Duration.Companion.milliseconds

/** 模型能力选择、流式请求重试及助手回复持久化；不持有会话调度状态。 */
class HarnessProviderRunner @Inject constructor(
    private val providerClient: ProviderClient,
    private val messageStore: SessionTreeStore,
    private val operationCoordinator: OperationCoordinator,
    private val stateMirrors: SessionStateMirrors,
    private val messageProjector: SessionMessageProjector,
    private val capabilityWriter: CapabilityEventWriter,
    private val agentEventLogger: AgentEventLogger,
    private val contextAssembler: ApiContextAssembler,
) {
    /** 按最新用户消息中的 @提及 过滤动态 MCP 工具，并写入能力挂载记录 */
    suspend fun resolveEffectiveModel(sessId: String, model: ModelConfig): ModelConfig {
        val msgs = messageProjector.messagesFlow(sessId).value
        val latestUserMessage = msgs.filterIsInstance<UserMessage>().lastOrNull()
        val latestUserText = latestUserMessage?.text.orEmpty()
        val mentionedNames = MentionExtractor.parse(latestUserText)
        val effectiveModel = if (mentionedNames.isNotEmpty()) {
            val matchedTools = model.dynamicMcpTools.filter { tool ->
                val sName = tool.serverName.lowercase()
                val sId = tool.serverId.lowercase()
                val tName = tool.name.lowercase()
                sName in mentionedNames || sId in mentionedNames || tName in mentionedNames
            }
            if (matchedTools.isNotEmpty()) model.copy(dynamicMcpTools = matchedTools) else model
        } else {
            model
        }
        capabilityWriter.writeIfMentioned(sessId, latestUserMessage?.id.orEmpty(), mentionedNames, effectiveModel)
        return effectiveModel
    }

    /** 流式调用 + 限流/网络退避重试。恢复不了的失败以 Failed 终态返回；取消与超过重试上限的原样抛出 */
    suspend fun callProviderWithRetry(
        sessId: String,
        model: ModelConfig,
        sessionEntity: HarnessSessionEntity?,
        sessionWorkspace: String,
        operationId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        retryPolicy: RetryPolicy,
        metrics: RunMetrics,
    ): TurnProviderOutcome {
        val streamText = StreamBuffer()
        val streamReasoning = StreamBuffer(maxChars = ProviderClient.MAX_STREAM_REASONING_CHARS)
        var streamed: ChatResult? = null
        var netRetry = 0
        suspend fun assembleFor(requestModel: ModelConfig) = contextAssembler.assemble(
            sessId = sessId,
            model = requestModel,
            workspacePath = sessionWorkspace,
            projectTypeOverride = sessionEntity?.projectType.orEmpty(),
            thinkingMode = stateMirrors.requestThinkingMode(sessId),
        )
        fun estimateTokens(messages: List<ApiMessage>) = messages.sumOf { message ->
            ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
                ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) +
                message.tool_calls.orEmpty().sumOf { call ->
                    ContextWindowPolicy.estimateTokens(call.function.name) +
                        ContextWindowPolicy.estimateTokens(call.function.arguments)
                } +
                message.imageUrls.size * ESTIMATED_IMAGE_TOKENS
        }
        // Context and prompt remain immutable during network retries. The configured model
        // window is authoritative: a transport heuristic must never persistently compact a
        // valid 128k/200k conversation down to 64k.
        var requestMessages = assembleFor(model)
        var imageStripped = false
        val estimatedRequestTokens = estimateTokens(requestMessages)
        val maxNetworkRetries = maxNetworkRetriesFor(estimatedRequestTokens, retryPolicy.maxRetries)
        // 显示的「分母」：瞬态传输故障（Socket/EOF/TLS/5xx）会被 effectiveRetryBudget 放宽到
        // 至少 TRANSIENT_MAX_RETRIES 次，因此预算不是固定值。这里用可变值在每次失败后同步为
        // 真正生效的预算，避免出现「(3/2)」这种分母小于当前次数的自相矛盾显示
        // （同一语义两处计算造成的 UI 与实际行为两张皮）。
        var attemptBudget = maxNetworkRetries
        if (maxNetworkRetries < retryPolicy.maxRetries) {
            agentEventLogger.log(
                sessId,
                "LargeContextRetryPolicy",
                "估算输入约 $estimatedRequestTokens tokens，大上下文网络重试限制为 $maxNetworkRetries 次",
            )
        }
        while (streamed == null) {
            try {
                // 分母为「本预算下最多允许多少次尝试」= 重试预算 + 1。
                stateMirrors.setStatus(sessId, "等待模型首个响应（${netRetry + 1}/${attemptBudget + 1}）")
                operationCoordinator.providerIntent(
                    operationId = operationId,
                    effectId = assistantId,
                    round = round,
                    attempt = netRetry + 1,
                    maxAttempts = attemptBudget + 1,
                )
                streamed = providerClient.chatStream(
                    model,
                    requestMessages,
                    onReasoning = { chunk ->
                        streamReasoning.append(chunk)
                        stateMirrors.setThinkingLive(sessId, true)
                        stateMirrors.recordThinkingObserved(sessId)
                        streamReasoning.publishIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                            messageProjector.streamReasoning(sessId, assistantId, assistantAt, it)
                        }
                    },
                    onToolProgress = { progress ->
                        stateMirrors.setThinkingLive(sessId, false)
                        stateMirrors.setStatus(
                            sessId,
                            if (progress.name == "write") {
                                "正在生成 write · +${progress.addedLines}"
                            } else {
                                "正在生成 edit · +${progress.addedLines} -${progress.deletedLines}"
                            },
                        )
                    },
                ) { chunk ->
                    stateMirrors.setStatus(sessId, "回复中")
                    streamText.append(chunk)
                    streamText.publishIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                        messageProjector.streamText(sessId, assistantId, assistantAt, it)
                    }
                }
                // 流式传输完毕，无条件刷新一次完整内容
                if (streamReasoning.length > 0) {
                    messageProjector.streamReasoning(sessId, assistantId, assistantAt, streamReasoning.toString())
                }
                if (streamText.length > 0) {
                    messageProjector.streamText(sessId, assistantId, assistantAt, streamText.toString())
                }
            } catch (cancellation: CancellationException) {
                agentEventLogger.log(sessId, "Cancelled", "用户主动取消执行")
                messageProjector.remove(sessId, assistantId)
                throw cancellation
            } catch (rateLimit: LlmRateLimitException) {
                currentCoroutineContext().ensureActive()
                if (rateLimit.quotaExhausted) {
                    stateMirrors.setThinkingLive(sessId, false)
                    agentEventLogger.log(sessId, "QuotaExhausted", rateLimit.message.orEmpty(), rateLimit)
                    // 移除空的流式气泡；错误通过 error state 展示，不写入消息历史，避免下一轮注入模型上下文
                    messageProjector.remove(sessId, assistantId)
                    val detail = rateLimit.message?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                    return TurnProviderOutcome.Failed("模型服务商额度已耗尽，无法继续执行。请充值、切换可用模型或更新 API Key。$detail")
                }
                netRetry++
                if (netRetry > maxNetworkRetries) throw rateLimit
                // 限流分支的预算即 maxNetworkRetries，同步显示用预算保持一致。
                attemptBudget = maxNetworkRetries
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                val waitSeconds = rateLimit.retryAfterSeconds ?: (netRetry * RETRY_BACKOFF_SEC).coerceAtMost(60L)
                stateMirrors.setStatus(sessId, "请求受限，${waitSeconds} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                agentEventLogger.log(sessId, "RateLimitRetry", "限流退避 ${waitSeconds}s，重试 $netRetry/$maxNetworkRetries", rateLimit)
                streamText.clear()
                streamReasoning.clear()
                messageProjector.remove(sessId, assistantId)
                for (remaining in waitSeconds downTo 1L) {
                    currentCoroutineContext().ensureActive()
                    stateMirrors.setStatus(sessId, "请求受限，${remaining} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                    delay(1000L.milliseconds)
                }
            } catch (io: IOException) {
                currentCoroutineContext().ensureActive()
                netRetry++
                // 连接被对端中止 / 读超时 / TLS 中断属于瞬态传输故障，与上下文规模无关。
                // 大上下文把重试预算压到 1 次会让一次 abort 就整轮失败，用户只能手动「接续」；
                // OkHttp 的 retryOnConnectionFailure 不覆盖已建立连接的中途断开，只能在应用层放宽。
                val transient = isTransientFailure(io)
                val retryBudget = effectiveRetryBudget(maxNetworkRetries, io)
                // 同步「显示用预算」为实际生效值，保证状态栏分母与真实重试行为一致。
                attemptBudget = retryBudget
                // netRetry 表示「这是第几次失败」；净重试预算为 retryBudget 次，故第 retryBudget+1 次失败即放弃。
                // 原写法 "重试 $netRetry/$retryBudget" 会被误读成「已执行第 retryBudget 次重试、仍在继续」。
                agentEventLogger.log(
                    sessId,
                    "NetworkRetry",
                    "网络中断，第 $netRetry 次失败（重试上限 $retryBudget 次" +
                        (if (transient) "，瞬态故障不受大上下文降级" else "") + "）：${io.message}",
                    io,
                )
                if (netRetry > retryBudget) throw io
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                stateMirrors.setStatus(sessId, "网络中断，自动重发中（第 $netRetry 次失败，上限 $retryBudget）")
                streamText.clear()
                streamReasoning.clear()
                messageProjector.remove(sessId, assistantId)
                delay(retryPolicy.delayForRetry(netRetry).milliseconds)
            } catch (throwable: Throwable) {
                stateMirrors.setThinkingLive(sessId, false)
                // 模型不支持图片输入（HTTP 400）时，剥离全部图片降级重试一次，避免整轮中断
                val lowerMsg = throwable.message.orEmpty().lowercase()
                val pendingImages = requestMessages.sumOf { it.imageUrls.size }
                if (!imageStripped && pendingImages > 0 &&
                    ("do not support image" in lowerMsg || "does not support image" in lowerMsg ||
                        "image input" in lowerMsg || "supports image" in lowerMsg ||
                        "image not supported" in lowerMsg ||
                        "不支持图片" in lowerMsg || "不支持图像" in lowerMsg ||
                        "图片输入" in lowerMsg || "图像输入" in lowerMsg)
                ) {
                    imageStripped = true
                    requestMessages = requestMessages.map { it.copy(imageUrls = emptyList()) }
                    agentEventLogger.log(
                        sessId, "VisionFallback",
                        "模型不支持图片输入，已剥离 $pendingImages 张图片降级重试", throwable,
                    )
                    streamText.clear()
                    streamReasoning.clear()
                    messageProjector.remove(sessId, assistantId)
                    continue
                }
                agentEventLogger.log(sessId, "ModelError", "LLM 调用失败: ${throwable.message}", throwable)
                if (streamText.length > 0) {
                    persistAssistant(
                        sessId,
                        assistantId,
                        assistantAt,
                        streamText.toString(),
                        streamReasoning.toString().ifBlank { null },
                        totalMs = now() - startedAt,
                        operationId = operationId,
                        round = round,
                    )
                } else {
                    // 移除空的流式气泡；错误通过 error state 展示，不写入消息历史
                    messageProjector.remove(sessId, assistantId)
                }
                return TurnProviderOutcome.Failed(friendly(throwable))
            }
        }
        messageProjector.endStreaming(sessId)
        return TurnProviderOutcome.Success(streamed, streamText.toString())
    }

    /** 回合结束后落库助手回复；无文本时只结算 usage 记录 */
    suspend fun persistAssistantOutput(
        sessId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        operationId: String,
        result: ChatResult,
        effectiveModel: ModelConfig,
        displayText: String,
        hasToolCalls: Boolean,
    ) {
        if (displayText.isNotEmpty()) {
            persistAssistant(
                sessId,
                assistantId,
                assistantAt,
                displayText,
                result.reasoningContent,
                totalMs = if (!hasToolCalls) now() - startedAt else null,
                operationId = operationId,
                round = round,
                usage = result.usage,
                model = effectiveModel,
            )
        } else {
            val usageEntity = result.usage.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = null,
                    provider = effectiveModel.provider,
                    modelId = effectiveModel.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, null, usage = usageEntity, round = round)
        }
    }

    private suspend fun persistAssistant(
        sessId: String,
        id: String,
        createdAt: Long,
        text: String,
        reasoning: String? = null,
        totalMs: Long? = null,
        operationId: String? = null,
        round: Int = 0,
        usage: ChatUsage? = null,
        model: ModelConfig? = null,
    ) {
        val message = AssistantText(
            id = id,
            createdAt = createdAt,
            text = text,
            reasoning = reasoning,
            totalMs = totalMs,
            modelId = model?.model,
            providerId = model?.provider,
            promptTokens = usage?.inputTokens?.takeIf { it > 0 }?.toInt(),
            completionTokens = usage?.outputTokens?.takeIf { it > 0 }?.toInt(),
            cachedTokens = usage?.cacheReadTokens?.takeIf { it > 0 }?.toInt(),
        )
        if (operationId != null) {
            val usageEntity = usage?.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = id,
                    provider = model?.provider,
                    modelId = model?.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, message, usage = usageEntity, round = round)
        } else {
            messageStore.append(sessId, message)
        }
        messageProjector.publishPersisted(sessId, message)
    }

    private fun now(): Long = System.currentTimeMillis()
    private fun friendly(throwable: Throwable): String =
        throwable.message?.take(200) ?: throwable::class.simpleName.orEmpty()

    companion object {
        private const val LARGE_REQUEST_TOKEN_THRESHOLD = 64_000
        private const val LARGE_REQUEST_MAX_RETRIES = 1

        /** 瞬态连接故障（断线 / 读超时 / TLS 中断）的最低重试预算，不受大上下文降级影响。 */
        private const val TRANSIENT_MAX_RETRIES = 3
        private const val ESTIMATED_IMAGE_TOKENS = 1_000

        internal fun maxNetworkRetriesFor(estimatedRequestTokens: Int, configuredRetries: Int): Int =
            if (estimatedRequestTokens >= LARGE_REQUEST_TOKEN_THRESHOLD) {
                minOf(configuredRetries, LARGE_REQUEST_MAX_RETRIES)
            } else {
                configuredRetries
            }

        /**
         * 实际重试预算 = 大上下文降级后的预算，但瞬态故障（断线 / 超时 / TLS 中断 / 上游 5xx）
         * 至少保留 [TRANSIENT_MAX_RETRIES] 次，不被降级到 1 次。
         */
        internal fun effectiveRetryBudget(largeContextRetries: Int, throwable: Throwable): Int =
            if (isTransientFailure(throwable)) {
                maxOf(largeContextRetries, TRANSIENT_MAX_RETRIES)
            } else {
                largeContextRetries
            }

        /**
         * 判断是否为「原样重发同一请求即可安全恢复」的瞬态故障：连接被对端中止、读超时、
         * TLS 层中断、流意外结束，以及上游 5xx（[TransientHttpException]，如 Cloudflare 524 / 503）。
         *
         * 这些故障与请求体大小、上下文规模无关，因此不受大上下文重试降级影响（见 [effectiveRetryBudget]）；
         * 否则一次 503 就会让长会话整轮失败，用户只能手动接续。
         * 沿 cause 链最多上溯 10 层，避免自引用造成死循环。
         */
        internal fun isTransientFailure(throwable: Throwable): Boolean {
            var cause: Throwable? = throwable
            var depth = 0
            while (cause != null && depth < 10) {
                if (cause is SocketException ||
                    cause is InterruptedIOException ||
                    cause is SSLException ||
                    cause is EOFException ||
                    cause is TransientHttpException
                ) {
                    return true
                }
                cause = cause.cause
                depth++
            }
            return false
        }
        const val RETRY_BACKOFF_MS = 1_000L
        const val RETRY_BACKOFF_SEC = 2L

    }
}
