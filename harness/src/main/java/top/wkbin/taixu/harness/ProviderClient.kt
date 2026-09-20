package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.tools.ProviderRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.wkbin.taixu.harness.mcp.McpToolApiName

/** HTTP 429 的结构化错误，供 Harness 区分临时限流与账户额度耗尽。 */
class LlmRateLimitException(
    message: String,
    val retryAfterSeconds: Long? = null,
    val quotaExhausted: Boolean = false,
) : IOException(message)

/** 上游临时故障（5xx，如 Cloudflare 524 origin timeout）：可退避重试，由网络重试路径统一处理。 */
class TransientHttpException(
    message: String,
    val httpCode: Int,
    val retryAfterSeconds: Long? = null,
) : IOException(message)

/** 可独立测试的 HTTP 层：OpenAI 兼容 chat/completions 请求与响应解析。 */
internal class ChatApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val requestCache: LlmRequestCache,
) {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            // 非流式请求缓存：相同请求在 TTL 内命中直接复用（省 token、更快）；只缓存成功结果，失败不落缓存
            val cacheKey = requestCacheKey(model, messages)
            requestCache.get(cacheKey)?.let { cached -> return@withContext cached }
            val httpCall = okHttpClient.newCall(buildRequest(model, messages, stream = false))
            // 与流式路径一致：取消时立即关闭 socket，否则阻塞的 execute()/body.string()
            // 不感知协程取消，用户点"停止"后最长要等满 callTimeout。
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { httpCall.cancel() }
            val result = try {
                httpCall.execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        if (response.code == 429) {
                            throw ProviderClient.rateLimitException(response.code, body, response.header("Retry-After"))
                        }
                        if (response.code in 500..599) {
                            throw ProviderClient.transientHttpException(response.code, body, response.header("Retry-After"))
                        }
                        throw IllegalStateException(ProviderClient.formatHttpErrorMessage(response.code, body))
                    }
                    if (!ProviderClient.looksLikeJsonResponse(body)) {
                        throw IllegalStateException(
                            ProviderClient.formatHttpErrorMessage(response.code, body),
                        )
                    }
                    val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), body)
                    val message = parsed.choices.firstOrNull()?.message ?: ChatResponseMessage()
                    val calls = message.tool_calls.orEmpty().mapNotNull { call ->
                        call.function.let { fn ->
                            if (fn.name.isBlank()) null else ApiToolCallSpec(
                                call.id.ifBlank { ToolCallIdNormalizer.normalize(null) },
                                fn.name,
                                fn.arguments.ifBlank { "{}" },
                            )
                        }
                    }
                    val (extractedContent, extractedReasoning) = ProviderClient.extractThinkTags(message.content, message.reasoning_content)
                    ChatResult(
                        content = extractedContent,
                        toolCalls = calls,
                        reasoningContent = extractedReasoning,
                        usage = parsed.usage?.toChatUsage() ?: ChatUsage(),
                    )
                }
            } finally {
                cancelHandle?.dispose()
            }
            requestCache.put(cacheKey, result)
            result
        }

    /** 缓存 key：模型 + 消息内容哈希（ModelConfig/ApiMessage 均为 data class，hashCode 基于内容）。
     *  见 [ProviderClient.requestCacheKey]——提为顶层以便 Anthropic/Responses 协议共用同一 key 空间。
     *  ModelConfig 的 protocol/responseApiEnabled 参与 hashCode，故跨协议天然隔离，不会互相污染。 */
    private fun requestCacheKey(model: ModelConfig, messages: List<ApiMessage>): String =
        ProviderClient.requestCacheKey(model, messages)

    /**
     * 流式调用：逐行读取 SSE（data: ...），每个内容增量立即通过 [onDelta] 回调
     * 交给 UI；工具调用参数按 index 分片累积。推理增量通过 [onReasoning] 回调。
     *
     * 默认携带 stream_options.include_usage 请求最终 usage 块；个别严格校验的
     * Provider 会因此 400，此时自动降级为不带该参数重试一次（请求在流开始前
     * 即失败，不会有增量重复发送的风险）。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult = try {
        executeStream(model, messages, onReasoning, onToolProgress, onDelta, includeUsage = true)
    } catch (rejected: IllegalStateException) {
        if (rejected.message?.contains("stream_options", ignoreCase = true) == true) {
            executeStream(model, messages, onReasoning, onToolProgress, onDelta, includeUsage = false)
        } else {
            throw rejected
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onToolProgress: (ToolCallStreamProgress) -> Unit,
        onDelta: (String) -> Unit,
        includeUsage: Boolean,
    ): ChatResult = withContext(Dispatchers.IO) {
        // 流式请求缓存（对齐非流式 chat）：相同请求（模型+消息）在 TTL 内命中直接回放最终结果，
        // 跳过网络省 token、更快。命中时把缓存的最终文本/工具调用一次性交给 UI（onDelta 回放），
        // 仅缓存成功结果，失败不落缓存。只缓存最终结果，不缓存流式中间增量。
        val cacheKey = requestCacheKey(model, messages)
        requestCache.get(cacheKey)?.let { cached ->
            // 命中回放：把缓存的最终文本一次性交给 UI（onDelta），推理内容回放给 onReasoning。
            // 工具进度（onToolProgress）只在真实流式时驱动进度条视觉，命中场景无增量可算，
            // 不回放——工具调用结果直接随返回的 ChatResult.toolCalls 提供给上层，不受影响。
            if (!cached.content.isNullOrBlank()) onDelta(cached.content)
            if (!cached.reasoningContent.isNullOrBlank()) onReasoning(cached.reasoningContent)
            return@withContext cached
        }
        val call = okHttpClient.newCall(buildRequest(model, messages, stream = true, includeUsage = includeUsage))
        // 关键：阻塞式 readUtf8Line() 不感知协程取消。用户点"停止"时必须主动 call.cancel()
        // 关闭底层 socket，阻塞读才会立刻抛出 IOException 退出——否则要等读超时，
        // 表现为"停止按钮没反应"。
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        // source.timeout() 只有收到 HTTP 响应头后才生效。看门狗覆盖 DNS、连接、请求体上传、
        // 等待响应头以及首个 SSE 事件的完整阶段，避免大请求仍静默等满 callTimeout。
        val firstEventTimeoutMs = ProviderClient.resolveFirstEventTimeoutMs(
            ProviderClient.estimateApiMessageTokens(messages),
        )
        val firstEventState = AtomicInteger(ProviderClient.FIRST_EVENT_WAITING)
        val firstEventWatchdog = launch {
            delay(firstEventTimeoutMs)
            if (firstEventState.compareAndSet(ProviderClient.FIRST_EVENT_WAITING, ProviderClient.FIRST_EVENT_TIMED_OUT)) {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body.string().take(512)
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, rawBody, response.header("Retry-After"))
                    }
                    if (response.code in 500..599) {
                        throw ProviderClient.transientHttpException(response.code, rawBody, response.header("Retry-After"))
                    }
                    throw IllegalStateException(ProviderClient.formatHttpErrorMessage(response.code, rawBody))
                }
                val source = response.body.source()
                val demuxer = ThinkTagStreamDemuxer(onReasoning, onDelta)
                val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
                var usage = ChatUsage()
                // 收尾标记：[DONE] 见到才算完整流。未见到时结果可能被对端截断——
                // 不落缓存（避免重试/下一轮回放残缺文本），但正常返回保持兼容
                //（个别网关确实不发 [DONE]，不能把它们全部判死）。
                var sawDone = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        sawDone = true
                        break
                    }
                    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    // 流中错误块（无 choices，此前被 ?: continue 静默吞掉当成功）：
                    // 显式抛 IOException 走既有重试路径
                    if (root?.get("error") is JsonObject) {
                        val message = (root["error"] as? JsonObject)
                            ?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: data.take(256)
                        throw IOException("流式请求失败：$message")
                    }
                    if (root != null && firstEventState.compareAndSet(
                            ProviderClient.FIRST_EVENT_WAITING,
                            ProviderClient.FIRST_EVENT_RECEIVED,
                        )
                    ) {
                        firstEventWatchdog.cancel()
                    }
                    // usage 块位于 chunk 顶层（stream_options.include_usage 时由最后一个 chunk 携带；
                    // DeepSeek/OpenRouter 等默认就会发）。后面的块覆盖前面的，保留最终值。
                    (root?.get("usage") as? JsonObject)?.let { block ->
                        parseUsageBlock(block)?.let { parsed -> usage = parsed }
                    }
                    val choice = root
                        ?.get("choices")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
                        ?: continue
                    val delta = choice["delta"] as? JsonObject
                    delta?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            demuxer.onContentChunk(chunk)
                        }
                    }
                    // 推理增量：兼容 reasoning_content（DeepSeek/GLM 等）与 reasoning（OpenRouter 等网关）两种字段名
                    val reasoningChunk = (delta?.get("reasoning_content") ?: delta?.get("reasoning"))
                        ?.let { it as? JsonPrimitive }?.contentOrNull
                    reasoningChunk?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            demuxer.onExplicitReasoningChunk(chunk)
                        }
                    }
                    // index 缺失时的 fallback 用元素在 tool_calls 数组中的迭代序号，
                    // 而不是固定 0——部分 OpenAI 兼容网关不分发 index，固定 0 会让
                    // 同一 chunk 内的多个并行工具调用相互覆盖、arguments 拼成非法 JSON。
                    delta?.get("tool_calls")?.let { it as? JsonArray }?.forEachIndexed { position, call2 ->
                        val callObj = call2 as? JsonObject ?: return@forEachIndexed
                        val index = callObj["index"]?.let { it as? JsonPrimitive }?.contentOrNull?.toIntOrNull()
                            ?: position
                        val accum = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        callObj["id"]?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { accum.id = it }
                        val function = callObj["function"] as? JsonObject
                        function?.get("name")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { accum.name = it }
                        function?.get("arguments")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.let { accum.arguments.append(it) }
                        accum.publishProgress(onToolProgress)
                    }
                }
                demuxer.flush()
                toolCalls.values.forEach { it.publishProgress(onToolProgress, force = true) }
                // 部分 OpenAI 兼容端对无参数函数不下发 arguments 分片，空串须兜底为 "{}"
                val calls = toolCalls.values.map {
                    ApiToolCallSpec(
                        it.id.ifBlank { ToolCallIdNormalizer.normalize(null) },
                        it.name,
                        it.arguments.toString().ifBlank { "{}" },
                    )
                }
                val streamResult = ChatResult(
                    content = demuxer.fullText.toString().ifEmpty { null },
                    toolCalls = calls,
                    reasoningContent = demuxer.fullReasoning.toString().ifEmpty { null },
                    usage = usage,
                )
                // 见到 [DONE] 才落缓存：截断流不缓存，避免把残缺回复回放给重试/下一轮
                if (sawDone) {
                    requestCache.put(cacheKey, streamResult)
                }
                streamResult
            }
        } catch (io: IOException) {
            if (firstEventState.get() == ProviderClient.FIRST_EVENT_TIMED_OUT) {
                throw SocketTimeoutException(
                    "等待模型首个响应超过 ${firstEventTimeoutMs / 1000}s",
                ).apply { initCause(io) }
            }
            throw io
        } finally {
            firstEventWatchdog.cancel()
            cancelHandle?.dispose()
        }
    }

    /** 手工解析流式 chunk 顶层 usage（各 Provider 字段不统一，DTO 反而脆）。 */
    private fun parseUsageBlock(block: JsonObject): ChatUsage? {
        val input = block["prompt_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: return null
        return ChatUsage(
            inputTokens = input,
            outputTokens = block["completion_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            reasoningTokens = (block["completion_tokens_details"] as? JsonObject)
                ?.get("reasoning_tokens")?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            cacheReadTokens = (block["prompt_tokens_details"] as? JsonObject)
                ?.get("cached_tokens")?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull()
                ?: block["prompt_cache_hit_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            cacheWriteTokens = block["prompt_cache_miss_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
        )
    }

    private fun buildRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean, includeUsage: Boolean = true): Request {
            val tools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model.dynamicMcpTools)
        // JSON_TEXT 模式：把工具 JSON 描述追加到首条 system 消息末尾，让模型在纯文本中输出工具调用。
        // 只注入首条：压缩摘要层也是 system 消息，全量注入会把数千 token 的工具 schema 复制多份，
        // 还把 JSON 定义拼在「早期历史摘要」末尾污染摘要语义（Anthropic/Responses 路径本就只注入一次）。
        val effectiveMessages = if (!model.pureChatMode && model.toolCallMode == ToolCallMode.JSON_TEXT && tools.isNotEmpty()) {
            val desc = ProviderClient.buildToolsTextDescription(tools)
            var injected = false
            messages.map { msg ->
                if (!injected && msg.role == "system" && !msg.content.isNullOrBlank()) {
                    injected = true
                    msg.copy(content = msg.content + "\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n" + desc)
                } else {
                    msg
                }
            }
        } else {
            messages
        }
        val requestJson = kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive(model.model))
            put("stream", kotlinx.serialization.json.JsonPrimitive(stream))
            if (stream && includeUsage) {
                // 请求最终 usage 块（OpenAI 官方规范字段；DeepSeek/DashScope/GLM/OpenRouter 均支持）
                put("stream_options", kotlinx.serialization.json.buildJsonObject {
                    put("include_usage", kotlinx.serialization.json.JsonPrimitive(true))
                })
            }
            model.temperature?.let { put("temperature", kotlinx.serialization.json.JsonPrimitive(it)) }
            model.maxTokens?.let { put("max_tokens", kotlinx.serialization.json.JsonPrimitive(it)) }
            model.topP?.let { put("top_p", kotlinx.serialization.json.JsonPrimitive(it)) }
            // 推理开关/强度：按厂商能力翻译（reasoning_effort / thinking_config / thinking / reasoning）
            ReasoningAdapter.openAiFields(model).forEach { (key, value) -> put(key, value) }
            // 工具调用：纯净模式与 DISABLED 模式完全不注入工具相关参数；仅 NATIVE 模式注入标准 tools
            if (!model.pureChatMode && model.toolCallMode == ToolCallMode.NATIVE && tools.isNotEmpty()) {
                put("tools", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ApiToolDefinition.serializer()), tools))
            }
            put("messages", kotlinx.serialization.json.buildJsonArray {
                effectiveMessages.forEach { msg ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive(msg.role))
                        if (msg.imageUrls.isNotEmpty()) {
                            put("content", kotlinx.serialization.json.buildJsonArray {
                                if (!msg.content.isNullOrBlank()) {
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                        put("text", kotlinx.serialization.json.JsonPrimitive(msg.content))
                                    })
                                }
                                msg.imageUrls.forEach { url ->
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("type", kotlinx.serialization.json.JsonPrimitive("image_url"))
                                        put("image_url", kotlinx.serialization.json.buildJsonObject {
                                            put("url", kotlinx.serialization.json.JsonPrimitive(url))
                                        })
                                    })
                                }
                            })
                        } else if (msg.content != null) {
                            put("content", kotlinx.serialization.json.JsonPrimitive(msg.content))
                        }
                        msg.reasoning_content?.let { put("reasoning_content", kotlinx.serialization.json.JsonPrimitive(it)) }
                        msg.tool_call_id?.let { put("tool_call_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                        msg.tool_calls?.let { calls ->
                            put("tool_calls", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ApiToolCall.serializer()), calls))
                        }
                    })
                }
            })
        }
        return Request.Builder()
            .url("${model.baseUrl.trimEnd('/')}/chat/completions")
            .header("Content-Type", "application/json")
            .apply {
                model.apiKey?.let { header("Authorization", "Bearer $it") }
                ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            // 直接序列化为 ByteArray，省去 JsonObject→String→ByteArray 中间的 String 副本，
            // 高峰时减少一份完整请求体大小的临时堆驻留。
            .post(requestJson.toString().encodeToByteArray().toRequestBody(ProviderClient.JSON_MEDIA_TYPE))
            .build()
    }
}

/** 分片累积一次工具调用的 id/name/arguments（OpenAI 与 Anthropic 流式均复用）。 */
internal data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
) {
    private var lastProgressAtNanos: Long = 0L
    private var lastProgress: Pair<Int, Int>? = null

    /**
     * 工具参数本身也是 SSE 分片。WRITE/EDIT 在完整 JSON 到达前不能执行，但可以从
     * 已收到的 JSON 字符串片段估算增删行数，避免大文件生成期间 UI 长时间停在“思考中”。
     */
    fun publishProgress(onProgress: (ToolCallStreamProgress) -> Unit, force: Boolean = false) {
        val normalizedName = name.trim().lowercase()
        if (normalizedName != "write" && normalizedName != "edit") return
        val now = System.nanoTime()
        if (!force && lastProgressAtNanos != 0L && now - lastProgressAtNanos < TOOL_PROGRESS_INTERVAL_NANOS) return

        val progress = when (normalizedName) {
            "write" -> ToolCallStreamProgress(
                name = normalizedName,
                addedLines = countPartialJsonStringLines(arguments, "content") ?: 0,
                deletedLines = 0,
            )
            else -> ToolCallStreamProgress(
                name = normalizedName,
                addedLines = countPartialJsonStringLines(arguments, "newText") ?: 0,
                deletedLines = countPartialJsonStringLines(arguments, "oldText") ?: 0,
            )
        }
        val counts = progress.addedLines to progress.deletedLines
        if (force || counts != lastProgress) {
            lastProgress = counts
            lastProgressAtNanos = now
            onProgress(progress)
        }
    }

    private companion object {
        const val TOOL_PROGRESS_INTERVAL_NANOS = 200_000_000L
    }
}

/** WRITE/EDIT 工具参数流的轻量进度；只用于 UI，不参与最终文件写入。 */
data class ToolCallStreamProgress(
    val name: String,
    val addedLines: Int,
    val deletedLines: Int,
)

/**
 * 从尚未闭合的 JSON 参数中读取指定字符串字段的当前行数。
 * JSON 中换行通常是 `\n`；转义反斜杠 `\\n` 不应误判为新行。
 */
internal fun countPartialJsonStringLines(arguments: CharSequence, fieldName: String): Int? {
    val key = "\"$fieldName\""
    var searchFrom = 0
    while (searchFrom < arguments.length) {
        val keyStart = arguments.indexOf(key, searchFrom)
        if (keyStart < 0) return null
        var cursor = keyStart + key.length
        while (cursor < arguments.length && arguments[cursor].isWhitespace()) cursor++
        if (cursor >= arguments.length) return null
        if (arguments[cursor] != ':') {
            searchFrom = keyStart + key.length
            continue
        }
        cursor++
        while (cursor < arguments.length && arguments[cursor].isWhitespace()) cursor++
        if (cursor >= arguments.length) return null
        if (arguments[cursor] != '"') {
            searchFrom = keyStart + key.length
            continue
        }
        cursor++
        var lines = 1
        var escaped = false
        while (cursor < arguments.length) {
            val char = arguments[cursor++]
            if (escaped) {
                if (char == 'n') lines++
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '\n' -> lines++
                    '"' -> return lines
                }
            }
        }
        return lines
    }
    return null
}

/** 解析后的模型运行配置。 */
data class ModelConfig(
    val name: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val apiKey: String?,
    /** 同一接口地址下参与轮询的 Key 池；为空时兼容使用 [apiKey]。 */
    val apiKeys: List<String> = emptyList(),
    /** 单 Key 每分钟请求上限；0 表示不限。 */
    val requestsPerMinutePerKey: Int = 0,
    /** 接入协议：OPENAI 兼容或 Anthropic Messages API。 */
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
    /** 推理参数（null = 服务端默认）。 */
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    /** 推理开关：AUTO = 跟随模型服务端默认。 */
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    /** 推理强度：null = 服务端默认。 */
    val reasoningEffort: ReasoningEffort? = null,
    /**
     * 工具调用模式：NATIVE = OpenAI 标准 function calling（注入 tools）；
     * JSON_TEXT = 工具列表写入系统提示词，模型用文本输出工具调用；
     * DISABLED = 禁用工具（纯聊天）。
     */
    val toolCallMode: ToolCallMode = ToolCallMode.NATIVE,
    val dynamicMcpTools: List<top.wkbin.taixu.core.model.McpToolInfo> = emptyList(),
    /** 上下文 Token 容量上限（如 128000，超出时滑动窗口压缩）。 */
    val contextTokens: Int? = null,
    /**
     * 单次输入上限（token）：每轮请求主动裁切的裁切基准；null = 未显式配置（按窗口推导）。
     * 与 contextTokens（窗口能力声明）语义不同，见 AiModelEntity.inputTokenLimit 的说明。
     */
    val inputTokenLimit: Int? = null,
    /**
     * 每模型压缩预算覆盖（对齐 pi compaction.modelOverrides）：
     * 压缩触发时保留的最近 token 上限（null = 不启用该收紧）。
     */
    val compactionKeepRecentTokens: Int? = null,
    /** 为 LLM 响应预留的 token（null = 使用内置默认 8192）。 */
    val compactionReserveTokens: Int? = null,
    /** 自定义请求头（多行 Key: Value 格式）。 */
    val customHeaders: String = "",
    /** 纯净排查模式：不注入系统提示词与工具。 */
    val pureChatMode: Boolean = false,
    /** 是否支持视觉多模态直接发送图片。 */
    val visionEnabled: Boolean = true,
    /** 是否使用 OpenAI Responses API（true = POST /responses；false = /chat/completions）。 */
    val responseApiEnabled: Boolean = false,
)

internal data class RequestedModelTarget(
    val profileId: String,
    val variant: String? = null,
)

internal fun selectRequestedModelTarget(
    profiles: List<top.wkbin.taixu.core.database.AiModelEntity>,
    selection: String,
): RequestedModelTarget? {
    val requested = selection.trim()
    if (requested.isBlank()) return null
    profiles.firstOrNull {
        it.id.equals(requested, ignoreCase = true) || it.name.equals(requested, ignoreCase = true)
    }?.let { return RequestedModelTarget(profileId = it.id) }
    profiles.forEach { entity ->
        val canonicalVariant = entity.model.split(',')
            .map { it.trim() }
            .firstOrNull { it.equals(requested, ignoreCase = true) }
        if (canonicalVariant != null) {
            return RequestedModelTarget(profileId = entity.id, variant = canonicalVariant)
        }
    }
    return null
}

/** LLM 接入协议：绝大多数厂商提供 OpenAI 兼容端点；Anthropic Claude 需要专用适配。 */
enum class ApiProtocol { OPENAI, ANTHROPIC }

/** 工具调用模式：NATIVE = 标准函数调用；JSON_TEXT = 文本 JSON 标记；DISABLED = 禁用。 */
enum class ToolCallMode { NATIVE, JSON_TEXT, DISABLED }

/** LLM 返回的一轮结果：纯文本 或 一个/多个工具调用。 */
data class ChatResult(
    val content: String?,
    val toolCalls: List<ApiToolCallSpec>,
    /** 推理模型输出的思考内容（DeepSeek 等），多轮对话需原样传回 API。 */
    val reasoningContent: String? = null,
    /** Provider 报告的本轮 token 用量；未报告时全部为 0。 */
    val usage: ChatUsage = ChatUsage(),
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * 一次补全的 token 用量（OpenAI usage 与 Anthropic usage 的统一投影）。
 * OpenAI: prompt/completion_tokens + details(cached/reasoning)；
 * Anthropic: input/output_tokens + cache_read/cache_creation_input_tokens；
 * DeepSeek: prompt_cache_hit/miss_tokens 映射为 cacheRead/cacheWrite。
 */
data class ChatUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    val hasData: Boolean
        get() = inputTokens > 0 || outputTokens > 0 || reasoningTokens > 0 ||
            cacheReadTokens > 0 || cacheWriteTokens > 0
}

data class ApiToolCallSpec(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

// ---------- OpenAI 兼容 chat/completions DTO ----------

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
    val tool_call_id: String? = null,
    val imageUrls: List<String> = emptyList(),
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    // 个别网关下发缺 function 字段的 tool_call：此前整个响应 decode 失败；
    // 给默认值把损失限制到单个工具调用（name 为空时下游本就按无效调用丢弃）
    val function: ApiFunctionCall = ApiFunctionCall(name = "", arguments = ""),
)

/**
 * Provider 出口的消息序列修复（最后一道防线）。
 *
 * OpenAI / DeepSeek / Anthropic 协议均要求：`role=tool` 的消息必须紧跟在
 * 包含对应 tool_call_id 的 `assistant(tool_calls)` 消息之后。中断恢复、
 * 结果跨用户边界、超长会话头部截断（branchTail）等异常历史可能破坏该约束，
 * 直接发送会被服务端 400 拒绝（DeepSeek 报
 * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"）。
 *
 * 修复规则：
 * - 错位/孤立的 tool 结果转写为 user 文本（信息保留，协议合法）；
 * - assistant.tool_calls 后缺失的结果补占位 tool 消息，避免整轮请求被拒。
 */
internal fun sanitizeApiTranscript(messages: List<ApiMessage>): List<ApiMessage> {
    var awaitingResultIds = LinkedHashSet<String>()
    val out = mutableListOf<ApiMessage>()

    fun flushMissingResults() {
        awaitingResultIds.forEach { id ->
            out.add(
                ApiMessage(
                    role = "tool",
                    content = "（工具结果缺失：历史中断或被裁剪，如仍需要请重新发起该工具调用）",
                    tool_call_id = id,
                ),
            )
        }
        awaitingResultIds = LinkedHashSet()
    }

    for (message in messages) {
        when {
            message.role == "assistant" && !message.tool_calls.isNullOrEmpty() -> {
                flushMissingResults()
                out.add(message)
                awaitingResultIds = message.tool_calls.orEmpty()
                    .mapTo(LinkedHashSet()) { it.id }
            }
            message.role == "tool" -> {
                val id = message.tool_call_id.orEmpty()
                if (id.isNotEmpty() && id in awaitingResultIds) {
                    out.add(message)
                    awaitingResultIds.remove(id)
                } else {
                    // 紧邻的前一条不是包含该 tool_call_id 的 assistant(tool_calls)：转写为 user 文本
                    out.add(
                        ApiMessage(
                            role = "user",
                            content = "【工具执行结果（历史顺序异常，已转写为文本）】\n${message.content.orEmpty()}",
                        ),
                    )
                }
            }
            else -> {
                if (awaitingResultIds.isNotEmpty()) flushMissingResults()
                out.add(message)
            }
        }
    }
    flushMissingResults()
    return out
}

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionDefinition,
)

@Serializable
data class ApiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsageResponse? = null,
)

/** OpenAI 兼容 usage 块（含 DeepSeek 缓存字段与 reasoning details）。 */
@Serializable
data class ChatUsageResponse(
    val prompt_tokens: Long? = null,
    val completion_tokens: Long? = null,
    val prompt_tokens_details: PromptTokensDetails? = null,
    val completion_tokens_details: CompletionTokensDetails? = null,
    val prompt_cache_hit_tokens: Long? = null,
    val prompt_cache_miss_tokens: Long? = null,
) {
    @Serializable
    data class PromptTokensDetails(val cached_tokens: Long? = null)

    @Serializable
    data class CompletionTokensDetails(val reasoning_tokens: Long? = null)

    fun toChatUsage(): ChatUsage = ChatUsage(
        inputTokens = prompt_tokens ?: 0,
        outputTokens = completion_tokens ?: 0,
        reasoningTokens = completion_tokens_details?.reasoning_tokens ?: 0,
        cacheReadTokens = prompt_tokens_details?.cached_tokens ?: prompt_cache_hit_tokens ?: 0,
        cacheWriteTokens = prompt_cache_miss_tokens ?: 0,
    )
}

@Serializable
data class ChatChoice(
    val message: ChatResponseMessage = ChatResponseMessage(),
)

@Serializable
data class ChatResponseMessage(
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
)

/**
 * 调用 LLM（OpenAI 兼容 chat/completions，支持 tools/tool_calls）。
 *
 * 模型配置优先取 [AiModelRepository] 中激活的 [top.wkbin.taixu.core.database.AiModelEntity]，
 * 未配置时回退到 [ProviderRepository]；API Key 始终从加密存储读取，绝不落库/落日志。
 */
@Singleton
class ProviderClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val providerRepository: ProviderRepository,
    private val modelDao: AiModelRepository,
    private val mcpManager: top.wkbin.taixu.harness.mcp.McpManager,
    private val settingsDataStore: AgentPreferences,
    private val json: Json,
) {
    private val apiKeyScheduler = ApiKeyScheduler()
    // 非流式专用：保留 callTimeout 总超时（含响应体读取），防止慢端点永久挂起。
    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    /** 非流式 + 流式请求缓存：常驻于 @Singleton 的 ProviderClient，跨请求共享（命中率才不为 0）。 */
    private val requestCache = LlmRequestCache()

    /** 本地请求缓存累计命中次数（跨 get 调用只增）；供指标层量化「缓存是否真的在命中」。 */
    val requestCacheHits: Int get() = requestCache.hitCount

    // 流式专用：callTimeout 计时覆盖整个 SSE 响应体读取，长生成（>5min）会被硬掐断、
    // 已流式内容全部丢弃。这里取消 callTimeout（0 = 不限制），长连接依靠
    // readTimeout（逐次 read 间隔超时，每个 SSE 事件都会重置）+ 首字看门狗兜底。
    // 连接池与拦截器通过 newBuilder() 与非流式 client 共享，无额外开销。
    private val streamHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun resolveModel(): ModelConfig = withContext(Dispatchers.IO) {
        val active = modelDao.activeModel()
        val baseConfig = if (active != null) {
            active.toModelConfig(providerRepository)
        } else {
            ModelConfig(
                name = "默认",
                provider = providerRepository.provider.first(),
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL },
                apiKey = providerRepository.readApiKey(),
            )
        }
        val dynamicMcp = runCatching { mcpManager.getActiveMcpTools() }.getOrDefault(emptyList())
        baseConfig.applyGlobalReasoningDepth().copy(dynamicMcpTools = dynamicMcp)
    }

    /**
     * 同 [resolveModel]，但额外做最小配置校验。[modelId] 非空且存在时优先使用该会话绑定档案，
     * [modelVariant] 用于覆盖档案里的默认模型名，实现同一供应商档案下的会话级模型隔离。
     * 否则回退到当前激活模型。无可用模型且未设置 API Key 时直接抛出明确异常。
     */
    suspend fun resolveConfigured(modelId: String? = null, modelVariant: String? = null): ModelConfig = withContext(Dispatchers.IO) {
        val requested = modelId?.takeIf { it.isNotBlank() }?.let { modelDao.findById(it) }
        val active = requested ?: modelDao.activeModel()
        val providerKey = providerRepository.readApiKey().orEmpty()
        if (active == null && providerKey.isBlank()) {
            throw IllegalStateException("未配置模型或 API Key，请先在「设置 → 模型」中添加并激活一个模型")
        }
        val baseConfig = if (active != null) {
            active.toModelConfig(providerRepository)
        } else {
            val provider = providerRepository.provider.first()
            val baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL }
            ModelConfig(
                name = "默认",
                provider = provider,
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = baseUrl,
                apiKey = providerKey.ifBlank { null },
                protocol = inferProtocol(baseUrl, provider),
            )
        }
        val sessionConfig = if (requested != null && !modelVariant.isNullOrBlank()) {
            baseConfig.copy(model = modelVariant.trim())
        } else {
            baseConfig
        }
        val dynamicMcp = runCatching { mcpManager.getActiveMcpTools() }.getOrDefault(emptyList())
        sessionConfig.applyGlobalReasoningDepth().copy(dynamicMcpTools = dynamicMcp)
    }

    /**
     * Resolve an explicit agent model selection without silently falling back to the active model.
     * The selection may be a saved profile id/name or one concrete model configured in a profile.
     */
    suspend fun resolveRequestedModel(
        selection: String?,
        inheritedProfileId: String? = null,
        inheritedVariant: String? = null,
    ): ModelConfig = withContext(Dispatchers.IO) {
        val requested = selection?.trim()?.takeIf { it.isNotBlank() && !it.equals("inherit", ignoreCase = true) }
            ?: return@withContext resolveConfigured(inheritedProfileId, inheritedVariant)
        val profiles = modelDao.observeAll().first()
        val target = selectRequestedModelTarget(profiles, requested) ?: throw IllegalArgumentException(
            "未找到模型选择“$requested”。请传入已保存的模型档案 ID/名称，或档案中已配置的具体模型名。",
        )
        resolveConfigured(target.profileId, target.variant)
    }

    /** Resolve a persisted role binding strictly, including its concrete model variant. */
    suspend fun resolveSavedModelProfile(profileId: String, variant: String?): ModelConfig = withContext(Dispatchers.IO) {
        val profile = modelDao.findById(profileId) ?: throw IllegalArgumentException(
            "子智能体绑定的模型档案“$profileId”已不存在，请在子智能体角色设置中重新选择。",
        )
        val requestedVariant = variant?.trim()?.takeIf { it.isNotBlank() }
        val canonicalVariant = requestedVariant?.let { requested ->
            profile.model.split(',').map { it.trim() }.firstOrNull { it.equals(requested, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "模型档案“${profile.name}”中已不存在模型“$requested”，请在子智能体角色设置中重新选择。",
                )
        }
        resolveConfigured(profile.id, canonicalVariant)
    }

    /**
     * 把「全局推理深度」设置应用到未显式配置的模型上。**只对该厂商实际支持的能力生效**：
     * - 先探测厂商能力（能否关闭 / 能否调强度），不支持的选项直接忽略，避免设置"改了却没反应"；
     * - 模型已显式关闭推理 -> 保持不动（用户意图优先）；
     * - 全局 disabled：仅当厂商 [ReasoningCapabilities.supportsDisable] 且模型 AUTO 时关闭推理；
     * - 全局 low/medium/high：仅当厂商 [ReasoningCapabilities.supportsEffort] 时按深度设置强度
     *   （模型 AUTO 则同时开启推理）；厂商不支持强度（如豆包）则保持 AUTO 跟随服务端默认；
     * - 全局 auto 或未知值 -> 不动。
     */
    private suspend fun ModelConfig.applyGlobalReasoningDepth(): ModelConfig {
        if (reasoningMode == ReasoningMode.DISABLED) return this
        val depth = settingsDataStore.defaultReasoningDepth.first()
        val caps = ReasoningAdapter.capabilities(this)
        return when (depth) {
            "disabled" ->
                if (reasoningMode == ReasoningMode.AUTO && caps.supportsDisable) {
                    copy(reasoningMode = ReasoningMode.DISABLED)
                } else {
                    this
                }
            "low", "medium", "high", "extreme", "max" -> {
                if (!caps.supportsEffort) return this // 不支持强度 -> 跟随服务端默认
                val effort = when (depth) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    "high" -> ReasoningEffort.HIGH
                    else -> ReasoningEffort.MAX
                }
                if (reasoningMode == ReasoningMode.AUTO) {
                    copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = effort)
                } else {
                    copy(reasoningEffort = reasoningEffort ?: effort)
                }
            }
            else -> this
        }
    }

    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        executeWithRotatedApiKey(model, apiKeyScheduler) { selected ->
            val sanitized = sanitizeApiTranscript(messages)
            when {
                // 用户显式开启 Responses API 时优先走该协议（仅对 OpenAI 兼容端点有意义）
                selected.responseApiEnabled -> ResponsesApi(httpClient, json, requestCache).chat(selected, sanitized)
                selected.protocol == ApiProtocol.ANTHROPIC -> AnthropicApi(httpClient, json, requestCache).chat(selected, sanitized)
                else -> ChatApi(httpClient, json, requestCache).chat(selected, sanitized)
            }
        }

    /** 流式调用：内容增量通过 [onDelta] 实时回调，推理增量通过 [onReasoning] 实时回调。 */
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult = executeWithRotatedApiKey(model, apiKeyScheduler) { selected ->
        val sanitized = sanitizeApiTranscript(messages)
        when {
            selected.responseApiEnabled -> ResponsesApi(streamHttpClient, json, requestCache).chatStream(
                selected,
                sanitized,
                onReasoning,
                onToolProgress,
                onDelta,
            )
            selected.protocol == ApiProtocol.ANTHROPIC -> AnthropicApi(streamHttpClient, json, requestCache).chatStream(
                selected,
                sanitized,
                onReasoning,
                onToolProgress,
                onDelta,
            )
            else -> ChatApi(streamHttpClient, json, requestCache).chatStream(
                selected,
                sanitized,
                onReasoning,
                onToolProgress,
                onDelta,
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        // 非流式 chat() 的总超时（含响应体读取）；须覆盖最大首字看门狗（240s），
        // 否则超大上下文 Prefill 会先被 callTimeout 掐断。
        // 流式路径已改用无 callTimeout 的 streamHttpClient（见类成员注释）。
        private const val CALL_TIMEOUT_MS = 5 * 60 * 1000L

        /** 大请求若迟迟没有任何合法 SSE 事件，应尽早失败并向 UI 暴露重试，而不是静默等满 callTimeout。 */
        internal const val FIRST_STREAM_EVENT_TIMEOUT_MS = 90_000L
        internal const val FIRST_EVENT_WAITING = 0
        internal const val FIRST_EVENT_RECEIVED = 1
        internal const val FIRST_EVENT_TIMED_OUT = 2

        /** 请求缓存 key：模型 + 消息内容 SHA-256 指纹。
         *  三协议（OpenAI Chat / Anthropic Messages / OpenAI Responses）共用同一 key 空间；
         *  ModelConfig 的 protocol/responseApiEnabled 参与指纹，故跨协议请求天然隔离。
         *  此前用两个 32 位 hashCode 拼接，长消息列表上存在非零碰撞概率——碰撞时会把
         *  A 对话的回复原样回放给 B 对话（静默上下文错乱），改用 SHA-256 消除。 */
        internal fun requestCacheKey(model: ModelConfig, messages: List<ApiMessage>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // apiKey 不参与指纹（避免敏感值进入中间字符串），其余字段全部参与
            digest.update(model.copy(apiKey = null).toString().encodeToByteArray())
            digest.update(byteArrayOf(0))
            messages.forEach { message ->
                digest.update(message.toString().encodeToByteArray())
                digest.update(byteArrayOf(1))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** 按预估输入规模放宽首字看门狗：超大上下文 Prefill 常超过默认 90s。 */
        internal fun resolveFirstEventTimeoutMs(estimatedTokens: Int): Long = when {
            estimatedTokens > 80_000 -> 240_000L
            estimatedTokens > 40_000 -> 150_000L
            else -> FIRST_STREAM_EVENT_TIMEOUT_MS
        }

        internal fun estimateApiMessageTokens(messages: List<ApiMessage>): Int =
            messages.sumOf { message ->
                ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
                    ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) +
                    // 图片按 base64 体积计（与 ContextWindowPolicy.tokensOf 的 1000 token/图
                    // 同量级）：多图请求的上传 + prefill 在慢速移动网络下可能远超 90s，
                    // 不计入会让首字看门狗误杀超时，且大请求网络重试上限仅 1 次。
                    message.imageUrls.sumOf { url ->
                        if (url.startsWith("data:image/", ignoreCase = true)) {
                            (url.length / 3).coerceAtLeast(1_000)
                        } else {
                            1_000
                        }
                    } +
                    (message.tool_calls?.sumOf { call ->
                        ContextWindowPolicy.estimateTokens(call.function.name) +
                            ContextWindowPolicy.estimateTokens(call.function.arguments)
                    } ?: 0)
            }

        /**
         * 单回合推理内容的累积上限（字符）。推理是执行过程草稿，不是长期上下文；
         * 超限后停止累积，防止异常模型的无界推理把会话内存与历史存储拖垮。
         */
        const val MAX_STREAM_REASONING_CHARS = 128 * 1024

        /** 流式增量上屏的发布间隔：SSE chunk 频率远高于帧率，逐 chunk 全量发布是 O(n²) 分配。
         *  放宽到 150ms 与 ChatScreen 的贴底节流对齐，降低高频重组造成的界面闪烁。 */
        const val STREAM_PUBLISH_INTERVAL_MS = 150L

        /** Room 实体 → 运行配置：推理参数原样透传，协议按 Base URL / 厂商名自动推断。 */
        private suspend fun top.wkbin.taixu.core.database.AiModelEntity.toModelConfig(
            providerRepository: top.wkbin.taixu.core.tools.ProviderRepository,
        ): ModelConfig {
            val baseUrl = this.baseUrl.ifBlank { DEFAULT_BASE_URL }
            val modelKeys = providerRepository.readModelApiKeys(secretRef)
            val fallbackKey = providerRepository.readApiKey().orEmpty().ifBlank { null }
            val effectiveKeys = modelKeys.ifEmpty { listOfNotNull(fallbackKey) }
            return ModelConfig(
                name = name,
                provider = provider,
                model = model.split(",").firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: model.trim(),
                baseUrl = baseUrl,
                apiKey = effectiveKeys.firstOrNull(),
                apiKeys = effectiveKeys,
                requestsPerMinutePerKey = requestsPerMinutePerKey.coerceAtLeast(0),
                protocol = inferProtocol(baseUrl, provider),
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                reasoningMode = when (reasoningMode?.lowercase()) {
                    "disabled" -> ReasoningMode.DISABLED
                    "enabled" -> ReasoningMode.ENABLED
                    else -> ReasoningMode.AUTO
                },
                reasoningEffort = when (reasoningEffort?.lowercase()) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    "high" -> ReasoningEffort.HIGH
                    "extreme", "max" -> ReasoningEffort.MAX
                    else -> null
                },
                toolCallMode = when (toolCallMode?.lowercase()) {
                    "json" -> ToolCallMode.JSON_TEXT
                    "disabled" -> ToolCallMode.DISABLED
                    else -> ToolCallMode.NATIVE
                },
                contextTokens = contextTokens,
                inputTokenLimit = inputTokenLimit,
                compactionKeepRecentTokens = compactionKeepRecentTokens,
                compactionReserveTokens = compactionReserveTokens,
                customHeaders = customHeaders,
                pureChatMode = pureChatMode,
                visionEnabled = visionEnabled,
                responseApiEnabled = responseApiEnabled,
            )
        }

        /** 解析多行自定义请求头（格式为 Key: Value，支持忽略空行与 # 注释） */
        fun parseCustomHeaders(raw: String): List<Pair<String, String>> {
            if (raw.isBlank()) return emptyList()
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val name = line.substring(0, colonIndex).trim()
                        val value = line.substring(colonIndex + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) name to value else null
                    } else null
                }
                .toList()
        }

        /** Anthropic 协议自动识别：官方域名或厂商名含 anthropic/claude。 */
        fun inferProtocol(baseUrl: String, provider: String): ApiProtocol {
            val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull().orEmpty()
            val providerLower = provider.lowercase()
            return if (host == "api.anthropic.com" ||
                providerLower.contains("anthropic") ||
                providerLower.contains("claude")
            ) {
                ApiProtocol.ANTHROPIC
            } else {
                ApiProtocol.OPENAI
            }
        }
        /**
         * 检测响应体是否不像 JSON —— 用于在 [formatHttpErrorMessage] /
         * ChatApi / AnthropicApi 的解析前短路，避免把 HTML / 纯文本 /
         * BOM 头部的内容丢给 kotlinx.serialization 抛出一串反混淆栈。
         *
         * - UTF-8 BOM (U+FEFF) 不被 Kotlin 的 [String.trimStart] 当作空白，
         *   但确实会让 JSON 解析器从 offset 1 开始而误判；这里先剔除。
         * - "<!doctype html"、"<html"、纯文本错误页（Cloudflare / Nginx /
         *   代理登录页）是最常见的"假装 JSON"响应。
         */
        internal fun looksLikeJsonResponse(body: String): Boolean {
            val stripped = if (body.isNotEmpty() && body[0] == '\uFEFF') body.substring(1) else body
            val prefix = stripped.trimStart().take(64).lowercase()
            if (prefix.isEmpty()) return false
            return prefix.startsWith("{") || prefix.startsWith("[")
        }

        /**
         * 判定响应是否为「上下文 / 请求体超限」类错误：HTTP 413，或响应体含服务端惯用的
         * 上下文超限关键词（context_length_exceeded / maximum context length / too many tokens …）。
         *
         * 之所以要按关键词兜底：部分网关用 HTTP 400 而非 413 返回同类错误；而 Nginx / 网关的
         * 413 正文是纯文本（"413 Request Entity Too Large"），无法走 JSON 解析分支。
         */
        internal fun isContextOverflowError(code: Int, body: String): Boolean {
            if (code == 413) return true
            val lower = body.lowercase()
            return lower.contains("context_length_exceeded") ||
                lower.contains("maximum context length") ||
                lower.contains("context length") ||
                lower.contains("reduce the length of the messages") ||
                lower.contains("too many tokens") ||
                lower.contains("request entity too large") ||
                lower.contains("payload too large") ||
                lower.contains("content too large")
        }

        fun formatHttpErrorMessage(code: Int, rawBody: String): String {
            val trimmedBody = if (rawBody.isNotEmpty() && rawBody[0] == '\uFEFF') rawBody.substring(1) else rawBody
            // HTTP 413 / 上下文超限：请求体（对话历史）超出服务端上限，与 Base URL、网络无关。
            // 必须先于「非 JSON ⇒ 疑似反向代理」兜底判定：Nginx / 网关的 413 响应体恰是纯文本，
            // 旧逻辑会把它误报成「Base URL 路由错误」，把用户引向完全错误的排查方向。
            if (isContextOverflowError(code, trimmedBody)) {
                return "请求内容超出该模型/服务的上下文上限 (HTTP $code)：当前对话历史过大，" +
                    "请压缩历史、调小「上下文预算」，或改用上下文窗口更大的模型。" +
                    "原始信息：${trimmedBody.take(200).trim()}"
            }
            // 远端把错误页（HTML/纯文本）当成 body 返回时，给出可读的固定文案，
            // 避免直接把 <html>... 拼到错误提示里刷屏。
            if (!looksLikeJsonResponse(trimmedBody)) {
                val kind = when {
                    trimmedBody.trimStart().startsWith("<", ignoreCase = true) -> "网页"
                    else -> "非 JSON 文本"
                }
                return "LLM 请求失败 (HTTP $code)：远端返回了$kind，可能为反向代理登录页、CDN 拦截或 Base URL 路由错误。请检查 Base URL 与网络。"
            }
            val errorMsg = runCatching {
                val obj = Json.parseToJsonElement(trimmedBody) as? JsonObject
                val err = obj?.get("error") as? JsonObject
                err?.get("message")?.let { it as? JsonPrimitive }?.contentOrNull
            }.getOrNull()?.trim() ?: trimmedBody.take(300).trim()

            val lowerMsg = errorMsg.lowercase()
            return when {
                code == 403 && (lowerMsg.contains("free quota") || lowerMsg.contains("quota exhausted") || lowerMsg.contains("free tier")) ->
                    "API 免费额度已耗尽 (HTTP 403)：请前往模型服务商控制台充值、关闭免费层限制，或在太墟中切换其他可用模型。"
                code == 401 || lowerMsg.contains("invalid api key") || lowerMsg.contains("unauthorized") ->
                    "API Key 无效或未授权 (HTTP 401)：请在模型设置中检查并更新该服务商的 API Key。"
                code == 429 || lowerMsg.contains("rate limit") || lowerMsg.contains("insufficient_quota") || lowerMsg.contains("quota") ->
                    "API 额度已用尽或请求频率超限 (HTTP $code)：$errorMsg"
                code == 404 ->
                    "模型名称或 API 地址不存在 (HTTP 404)：请检查模型名称是否拼写正确。"
                errorMsg.isNotBlank() ->
                    "LLM 请求失败 (HTTP $code)：$errorMsg"
                else ->
                    "LLM 请求失败 (HTTP $code)"
            }
        }

        internal fun rateLimitException(code: Int, rawBody: String, retryAfter: String?): LlmRateLimitException {
            val message = formatHttpErrorMessage(code, rawBody)
            val lower = message.lowercase()
            val quotaExhausted = listOf(
                "insufficient_quota",
                "quota exceeded",
                "allocated quota",
                "quota exhausted",
                "resource_exhausted",
                "余额",
                "额度已用尽",
            ).any { it in lower }
            val retrySeconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1L, 300L)
                ?: runCatching {
                    ZonedDateTime.parse(retryAfter?.trim().orEmpty(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toEpochSecond() - System.currentTimeMillis() / 1000L
                }.getOrNull()?.takeIf { it > 0 }?.coerceAtMost(300L)
            return LlmRateLimitException(message, retrySeconds, quotaExhausted)
        }

        /** 5xx 上游临时故障（如 Cloudflare 524 origin timeout）：包装为可退避重试的 IOException。 */
        internal fun transientHttpException(code: Int, rawBody: String, retryAfter: String?): TransientHttpException {
            val message = formatHttpErrorMessage(code, rawBody)
            val retrySeconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1L, 300L)
            return TransientHttpException(message, code, retrySeconds)
        }

        internal const val READ_TIMEOUT_MS = 5 * 60 * 1000L
        internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 工具 JSON Schema，与 ToolExecutor 的参数契约一一对应。 */
        val TOOLS: List<ApiToolDefinition> = listOf(
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "load_skill",
                    description = "加载技能（Skill）的完整说明。**开工前应先扫一遍系统提示末尾的「可用技能」目录**，" +
                        "判断本次任务是否命中某个技能的专业领域（如逆向、构建、Git 流程、上游 PR、CI 归因等）；" +
                        "命中则用本工具取回完整指导规则与资源路径，再按说明执行。" +
                        "技能是既成的专业做法沉淀（含踩过的坑与验收标准），先查再动手可避免重复摸索。只读。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"name":{"type":"string","description":"技能名称或触发命令（不含 / 前缀），须与目录中列出的一致"}},"required":["name"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "build_script",
                    description = "管理工坊构建脚本并挂载到项目。新旧依赖不兼容时，先检查项目 Gradle/Flutter 配置，再 create 脚本并 bind 当前项目。脚本接口：第 1 个参数是项目目录；Android 第 2 个参数是 Gradle task；Flutter 第 2 个参数是完整 build 参数。支持 list/get/create/update/delete/bind/unbind。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["list","get","create","update","delete","bind","unbind"]},"id":{"type":"string","description":"脚本 ID；get/update/delete/bind 必需"},"name":{"type":"string","description":"脚本名称"},"description":{"type":"string","description":"适用依赖版本与用途"},"project_type":{"type":"string","enum":["android","flutter"]},"content":{"type":"string","description":"完整 POSIX shell 脚本，最大 200 KB"},"project":{"type":"string","description":"项目名称；省略时使用当前工作区"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "history_search",
                    description = "在当前会话的完整历史中按关键词检索旧消息。压缩摘要缺少关键细节时先用它定位消息，再用 history_read 读取原文。只读，不修改历史。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"query":{"type":"string","description":"要检索的关键词、文件名、错误信息或约束"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"最多返回命中条数，默认 8"}},"required":["query"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "history_read",
                    description = "读取当前会话某条历史消息的原文。使用 history_search 返回的 message_id，或使用稳定的历史 index。单条返回有大小上限。只读。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"message_id":{"type":"string","description":"history_search 返回的消息 ID"},"index":{"type":"integer","minimum":0,"description":"历史消息的 0 起始索引；与 message_id 二选一"}},"anyOf":[{"required":["message_id"]},{"required":["index"]}]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "read",
                    description = "读取文件内容（UTF-8，单文件上限 1MB）。路径可用相对路径或以 /workspace/ 开头。优先用它检查文件内容，而不是用 cat/sed。大文件用 offset（1 起始行号）和 limit（行数）分页读取，返回头部会标注总行数与当前窗口。若文件不存在或读取失败，用 base 的 ls/find 定位。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"offset":{"type":"integer","description":"起始行号（1 起始），可选"},"limit":{"type":"integer","description":"读取的最大行数，可选"}},"required":["path"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "write",
                    description = "创建或完全覆盖文件内容，自动创建父目录。只用于新文件或完整重写；若只想修改局部内容，请改用 edit。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "edit",
                    description = "在文件中做精确文本替换。oldText 必须与原文逐字匹配且唯一，一次可传多个替换，但每个不能重叠或嵌套。oldText 重复或匹配多处会失败——先 read 确认内容再改。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "base",
                    description = "在 Debian Linux 沙箱中执行前台 shell 命令，返回退出码/stdout/stderr。用于安装软件、运行脚本、检查状态和执行构建。默认超时由用户在 Agent 设置中配置；可用 timeout_seconds 为单次调用指定 1-900 秒。常驻服务不要使用 nohup 或 &，应改用 process 工具。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"},"cwd":{"type":"string","description":"工作目录；关联工作区时默认使用工作区，否则为 /root"},"timeout_seconds":{"type":"integer","minimum":1,"maximum":900,"description":"可选的单次超时秒数；省略时使用用户设置的默认值"}},"required":["command"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "process",
                    description = "管理需要跨工具调用持续运行的 PRoot 后台进程。start 的命令必须以前台模式运行，由 TaiXu 托管生命周期；不要使用 nohup、& 或自行 daemonize。使用 status/logs/list/stop 查询和停止。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["start","status","logs","list","stop"]},"id":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,63}$","description":"稳定的进程标识；list 不需要"},"command":{"type":"string","description":"start 时必需，需以前台模式持续运行"},"cwd":{"type":"string","description":"start 的工作目录"},"tail_lines":{"type":"integer","minimum":1,"maximum":500,"description":"logs 返回的末尾行数，默认 120"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "host",
                    description = "在 Android 宿主侧执行系统设置、应用管理、Logcat 或屏幕 GUI 自动化。抓取日志（logcat）首选内置无线 ADB（无需 Shizuku/Root 授权，支持可选指定 port），其余特权操作需 Shizuku 或 Root。GUI 原语（screen_click/double_click/long_press/swipe/scroll/input_text/key）走 HostGuiToolkit：无障碍全局手势 → cmd input → bin input 自动降级；中文输入走剪贴板粘贴。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["status","exec","settings_get","settings_put","package_list","package_disable","package_enable","package_uninstall_user","app_list","app_freeze","app_unfreeze","app_grant_permission","logcat","device_status","screen_observe","screen_click","screen_double_click","screen_long_press","screen_swipe","screen_scroll","screen_input_text","paste_text","screen_key","app_launch","screen_capture"]},"command":{"type":"string","description":"仅 exec 使用的原始宿主命令"},"namespace":{"type":"string","enum":["system","secure","global"],"description":"settings_get/settings_put 的设置命名空间"},"key":{"type":"string","description":"系统设置键名，或 screen_key 的按键名(back/home/recents/enter/delete/paste/power)"},"value":{"type":"string","description":"settings_put 的值"},"text":{"type":"string","description":"screen_input_text/paste_text 要粘贴的文本"},"x":{"type":"integer","description":"screen_click/double_click/long_press 的点击 X 坐标"},"y":{"type":"integer","description":"screen_click/double_click/long_press 的点击 Y 坐标"},"x1":{"type":"integer","description":"screen_swipe 起点 X 坐标"},"y1":{"type":"integer","description":"screen_swipe 起点 Y 坐标"},"x2":{"type":"integer","description":"screen_swipe 终点 X 坐标"},"y2":{"type":"integer","description":"screen_swipe 终点 Y 坐标"},"duration_ms":{"type":"integer","description":"swipe/long_press/scroll 持续时间毫秒"},"direction":{"type":"string","enum":["up","down","left","right"],"description":"screen_scroll 方向"},"distance_ratio":{"type":"number","description":"screen_scroll 幅度 0.15-0.8"},"package":{"type":"string","description":"应用操作或 logcat PID 过滤的 Android 包名（如 com.tencent.mm）"},"path":{"type":"string","description":"screen_capture 保存截图的目标绝对路径"},"permission":{"type":"string","description":"app_grant_permission 的 Android 权限名"},"query":{"type":"string","description":"app_list 的包名或应用名搜索词"},"include_system":{"type":"boolean","description":"app_list 是否显示系统应用，默认 false"},"limit":{"type":"integer","minimum":1,"maximum":200,"description":"app_list 返回数量，默认 50"},"user":{"type":"integer","minimum":0,"maximum":999,"description":"Android 用户 ID，默认 0"},"filter":{"type":"string","description":"package_list 的可选字面量过滤词"},"tail_lines":{"type":"integer","minimum":1,"maximum":2000,"description":"logcat 返回行数，默认 200"},"tag":{"type":"string","description":"logcat 的可选 tag"},"priority":{"type":"string","enum":["V","D","I","W","E","F"],"description":"logcat 最低优先级，默认 V"},"keyword":{"type":"string","description":"logcat 可选关键词（忽略大小写）"},"port":{"type":"integer","minimum":1,"maximum":65535,"description":"无线 ADB 端口（如 12345），logcat 时可选显式指定"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "download",
                    description = "使用内置 HTTPS 下载器把远程文件保存到工作区。支持 HTTP Range 断点续传、自动重试、最大文件大小限制和可选 SHA-256 校验；当前为单连接续传，不是多线程分片。destination 必须位于当前工作区内，不要填写宿主机绝对路径。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"url":{"type":"string","description":"HTTPS 下载地址"},"destination":{"type":"string","description":"工作区内目标路径，例如 dist/tool.tar.gz"},"sha256":{"type":"string","description":"可选 SHA-256 十六进制摘要"},"max_attempts":{"type":"integer","description":"可选最大尝试次数，1-10，默认 3"},"max_bytes":{"type":"integer","description":"可选最大文件大小（字节），默认 1 GiB，最大 4 GiB"}},"required":["url","destination"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "memory",
                    description = "长期语义与事实记忆管理：持久化记录用户的长期偏好、项目架构规范、稳定事实。支持 action: save, query, list, delete。scope: global, project, session。kind: preference, rule, fact, project_info。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["save","query","list","delete"],"description":"操作动作"},"key":{"type":"string","description":"记忆键名"},"value":{"type":"string","description":"记忆内容（save 必需）"},"kind":{"type":"string","enum":["preference","rule","fact","project_info"],"description":"记忆类型"},"scope":{"type":"string","enum":["global","project","session"],"description":"记忆作用域"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "plan",
                    description = "结构化多步骤任务规划管理：拆解长任务子步骤并持续跟踪推进进度。当任务预计需要 3 次以上工具调用、存在多个相互依赖的执行阶段、失败后需要分支排查，或会修改多个文件/系统状态时，第一轮工具调用先 replace_active 建立规划，每步完成后 advance；简单单步或双步任务不要建 plan。详细规则见 workflow 规则块（未注入时可用 load_rule 获取）。支持 action: replace_active, get_active, advance, clear_active。advance 有两种用法：① 只推进某一步 —— 传 stepId（步骤 id 或 1 起始序号）+ stepStatus（如 completed），无需重传全部步骤；② 整体替换 —— 传完整 steps 数组。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["replace_active","get_active","advance","clear_active"],"description":"规划操作动作"},"goal":{"type":"string","description":"任务总体目标"},"steps":{"type":"array","description":"规划步骤列表（每个步骤包含 id, title, status: pending|in_progress|completed|failed）。replace_active 必填；advance 时可选（传则整体替换）","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"status":{"type":"string"}},"required":["id","title","status"]}},"stepId":{"type":"string","description":"advance 专用：要更新的步骤标识，取步骤的 id 或 1 起始序号。与 stepStatus 搭配可只更新单步，无需重传 steps"},"stepStatus":{"type":"string","description":"advance 专用：stepId 对应步骤的新状态，如 completed / in_progress / pending"},"status":{"type":"string","enum":["active","completed","cancelled"],"description":"可选：计划整体生命周期状态（仅限 active/completed/cancelled，步骤进度请写在 steps[].status）"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "scratchpad",
                    description = "任务/会话局部工作草稿便签：临时记录排查假说、分析草稿、当前子目标与阻塞点（Blockers）。支持 action: save, get, list, delete, clear。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["save","get","list","delete","clear"],"description":"草稿操作动作"},"key":{"type":"string","description":"草稿键名"},"value":{"type":"string","description":"草稿内容（save 必需）"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "invoke_subagent",
                    description = "按研发部门和简短专业关键词从本地索引解析角色，并发派发隔离子智能体。用户枚举多个独立子任务时，必须在同一次调用的 subagents 数组中完整提交，禁止逐个派发；结果保持数组顺序。writePaths=[] 表示只读并行，精确路径表示局部写租约，[\"*\"] 表示整工作区独占写入。写租约对 write/edit/download 是强制闸门：writePaths=[] 的子任务调用这些工具会被直接拦截，越界路径同样拦截，需要落盘必须先声明具体路径；base 的 shell 写不受闸门约束，写租约也只在同一次调用内协调。子任务需要审批类操作（后台 Lane 无法暂停审批）时会作为待办上交，必须由你在主会话重新发起。候选目录不会进入主对话。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"subagents":{"type":"array","description":"一次性提交的完整独立子任务列表；用户枚举 N 项时必须包含全部 N 项","minItems":1,"maxItems":6,"items":{"type":"object","properties":{"taskName":{"type":"string","description":"简短子任务名称"},"department":{"type":"string","enum":["engineering","design","product","project-management","testing","security","game-development","spatial-computing","specialized"],"description":"先选研发部门，匹配严格限制在该部门"},"agentQuery":{"type":"string","minLength":2,"maxLength":80,"description":"2-5 个简短英文专业关键词，如 frontend react、mobile android、test automation；不要复制完整任务"},"role":{"type":"string","description":"可选：仅兼容已知 profile id/name 的精确覆盖；存在时优先于索引匹配"},"prompt":{"type":"string","description":"详细任务指令与交付要求"},"writePaths":{"type":"array","description":"必须声明。纯调研/分析填空数组 []；修改文件时列出精确相对路径；只有整工作区独占写入才填 [\"*\"]","items":{"type":"string"}},"model":{"type":"string","description":"可选：已保存模型档案的 ID/名称，或档案中已配置的具体模型名；优先于角色默认模型。传 inherit 强制继承父会话模型；不填则使用角色默认模型，角色未配置时继承父会话"}},"required":["taskName","department","agentQuery","prompt","writePaths"]}}},"required":["subagents"]}""",

                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "invoke_dual_agent",
                    description = "用物理隔离的 Planner/Executor 双智能体执行复杂多步骤任务。Planner 只规划验收，Executor 按 DAG 依赖并行使用工具；适合需要多阶段实施与验证的任务。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"prompt":{"type":"string","description":"需要双智能体完成的完整任务与验收标准"},"planner_model":{"type":"string","description":"可选：Planner 使用的已保存模型档案 ID/名称，或档案中已配置的具体模型名；省略则继承当前会话"},"executor_model":{"type":"string","description":"可选：Executor 使用的已保存模型档案 ID/名称，或档案中已配置的具体模型名；省略则继承当前会话"},"max_steps":{"type":"integer","minimum":1,"maximum":30,"description":"最大规划轮数，默认 10"}},"required":["prompt"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "load_rule",
                    description = "按需加载系统提示词的详细规则块（workflow / code-navigation / security / memory / environment-proot / tools）。当当前任务需要某块规则但系统提示词中未注入时调用；只读，无副作用。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"rule":{"type":"string","enum":["workflow","code-navigation","security","memory","environment-proot","tools"],"description":"要加载的规则块名称"}},"required":["rule"]}""",
                    ).jsonObject,
                ),
            ),
        )

        /** 组装静态基础工具 + 动态 MCP 插件工具 */
        fun buildDynamicTools(mcpTools: List<top.wkbin.taixu.core.model.McpToolInfo> = emptyList()): List<ApiToolDefinition> {
            val list = TOOLS.toMutableList()
            // 工具数组序列化在 messages 之前，其顺序抖动会击穿 provider prefix cache 的整个前缀。
            // MCP 工具来自并发发现（awaitAll），单服务内顺序取决于服务端返回，未必稳定；
            // 这里按 (serverId, name) 显式排序，保证同一组启用服务下发的工具数组逐字节一致。
            mcpTools.sortedWith(compareBy({ it.serverId }, { it.name })).forEach { mcp ->
                val fullToolName = McpToolApiName.encode(mcp)
                val params = runCatching {
                    Json.parseToJsonElement(mcp.parametersJson).jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                list.add(
                    ApiToolDefinition(
                        function = ApiFunctionDefinition(
                            name = fullToolName,
                            description = "【MCP 插件: ${mcp.serverName}】${mcp.description}",
                            parameters = params,
                        ),
                    ),
                )
            }
            return list
        }

        /**
         * 把工具定义转成给模型看的 JSON 文本描述（JSON_TEXT 工具调用模式使用）。
         * 每个工具一行 JSON，格式与 OpenAI function calling 一致，模型按 name/parameters 输出调用。
         */
        fun buildToolsTextDescription(tools: List<ApiToolDefinition>): String {
            if (tools.isEmpty()) return "（无可用工具）"
            return tools.joinToString("\n") { tool ->
                val fn = tool.function
                buildString {
                    append("- ").append(fn.name).append(": ").append(fn.description)
                    append("\n  参数 JSON Schema: ").append(fn.parameters.toString())
                }
            }
        }

        /**
         * 从模型回复内容中提取 <think>...</think> 标签内容并与正文分离。
         */
        fun extractThinkTags(content: String?, explicitReasoning: String?): Pair<String?, String?> {
            if (content.isNullOrBlank()) return Pair(content, explicitReasoning)
            val thinkPattern = Regex("""(?s)<think>(.*?)</think>""")
            val match = thinkPattern.find(content)
            return if (match != null) {
                val extractedReasoning = match.groupValues[1].trim()
                val strippedContent = thinkPattern.replace(content, "").trim().ifEmpty { null }
                val finalReasoning = explicitReasoning?.takeIf { it.isNotBlank() } ?: extractedReasoning.ifEmpty { null }
                Pair(strippedContent, finalReasoning)
            } else {
                val unclosedPattern = Regex("""(?s)<think>(.*)""")
                val unclosedMatch = unclosedPattern.find(content)
                if (unclosedMatch != null) {
                    val extractedReasoning = unclosedMatch.groupValues[1].trim()
                    val strippedContent = unclosedPattern.replace(content, "").trim().ifEmpty { null }
                    val finalReasoning = explicitReasoning?.takeIf { it.isNotBlank() } ?: extractedReasoning.ifEmpty { null }
                    Pair(strippedContent, finalReasoning)
                } else {
                    Pair(content, explicitReasoning)
                }
            }
        }

        /**
         * 剥离文本中的 <think>...</think> 标签残余。
         */
        fun stripThinkTags(content: String?): String? {
            if (content == null) return null
            val thinkPattern = Regex("""(?s)<think>(.*?)</think>""")
            val unclosedPattern = Regex("""(?s)<think>(.*)""")
            val stripped = unclosedPattern.replace(thinkPattern.replace(content, ""), "").trim()
            return stripped.ifEmpty { null }
        }
    }
}

/**
 * 流式 chunk 的 <think> 标签与思考/正文智能分流器。
 * 兼容：原生 reasoning_chunk、在 content 中输出 <think>...</think> 标签以及跨 chunk 标签碎片。
 * 内置思维链死循环/重复自旋检测与保护机制。
 */
internal class ThinkTagStreamDemuxer(
    private val onReasoning: (String) -> Unit,
    private val onDelta: (String) -> Unit,
    private val maxReasoningChars: Int = ProviderClient.MAX_STREAM_REASONING_CHARS,
) {
    val fullText = StringBuilder()
    val fullReasoning = StringBuilder()

    private var inThinkTag = false
    private val pendingBuffer = StringBuilder()
    private var reasoningMutedDueToLoop = false

    fun onExplicitReasoningChunk(chunk: String) {
        if (chunk.isEmpty()) return
        appendReasoning(chunk)
    }

    fun onContentChunk(chunk: String) {
        if (chunk.isEmpty()) return
        pendingBuffer.append(chunk)
        processPending()
    }

    fun flush() {
        if (pendingBuffer.isNotEmpty()) {
            val leftover = pendingBuffer.toString()
            pendingBuffer.clear()
            if (inThinkTag) {
                appendReasoning(leftover)
            } else {
                appendText(leftover)
            }
        }
    }

    private fun processPending() {
        while (pendingBuffer.isNotEmpty()) {
            if (!inThinkTag) {
                val thinkStartIdx = pendingBuffer.indexOf("<think>")
                if (thinkStartIdx != -1) {
                    val before = pendingBuffer.substring(0, thinkStartIdx)
                    if (before.isNotEmpty()) {
                        appendText(before)
                    }
                    pendingBuffer.delete(0, thinkStartIdx + "<think>".length)
                    inThinkTag = true
                } else {
                    val possiblePrefixLen = matchTrailingPrefix(pendingBuffer, "<think>")
                    if (possiblePrefixLen > 0) {
                        val emitLen = pendingBuffer.length - possiblePrefixLen
                        if (emitLen > 0) {
                            val toEmit = pendingBuffer.substring(0, emitLen)
                            appendText(toEmit)
                            pendingBuffer.delete(0, emitLen)
                        }
                        break
                    } else {
                        val textToEmit = pendingBuffer.toString()
                        pendingBuffer.clear()
                        appendText(textToEmit)
                    }
                }
            } else {
                val thinkEndIdx = pendingBuffer.indexOf("</think>")
                if (thinkEndIdx != -1) {
                    val reasoningContent = pendingBuffer.substring(0, thinkEndIdx)
                    if (reasoningContent.isNotEmpty()) {
                        appendReasoning(reasoningContent)
                    }
                    pendingBuffer.delete(0, thinkEndIdx + "</think>".length)
                    inThinkTag = false
                } else {
                    val possiblePrefixLen = matchTrailingPrefix(pendingBuffer, "</think>")
                    if (possiblePrefixLen > 0) {
                        val emitLen = pendingBuffer.length - possiblePrefixLen
                        if (emitLen > 0) {
                            val toEmit = pendingBuffer.substring(0, emitLen)
                            appendReasoning(toEmit)
                            pendingBuffer.delete(0, emitLen)
                        }
                        break
                    } else {
                        val reasoningToEmit = pendingBuffer.toString()
                        pendingBuffer.clear()
                        appendReasoning(reasoningToEmit)
                    }
                }
            }
        }
    }

    private fun appendText(str: String) {
        if (str.isEmpty()) return
        fullText.append(str)
        onDelta(str)
    }

    private fun appendReasoning(str: String) {
        if (str.isEmpty()) return
        if (reasoningMutedDueToLoop) return

        if (detectRepetitionLoop(fullReasoning, str)) {
            reasoningMutedDueToLoop = true
            val notice = "\n[太墟提示：检测到思维链重复自旋死循环，已自动截断冗余思考内容并继续执行]\n"
            if (fullReasoning.length + notice.length <= maxReasoningChars) {
                fullReasoning.append(notice)
            }
            onReasoning(notice)
            return
        }

        if (fullReasoning.length < maxReasoningChars) {
            val takeCount = (maxReasoningChars - fullReasoning.length).coerceAtMost(str.length)
            fullReasoning.append(str.take(takeCount))
        }
        onReasoning(str)
    }

    private fun matchTrailingPrefix(sb: CharSequence, target: String): Int {
        for (len in target.length - 1 downTo 1) {
            if (sb.length >= len) {
                val sub = sb.subSequence(sb.length - len, sb.length)
                if (target.startsWith(sub)) {
                    return len
                }
            }
        }
        return 0
    }

    private fun detectRepetitionLoop(history: StringBuilder, newChunk: String): Boolean {
        if (history.length < 200) return false
        val recent = history.takeLast(300).toString() + newChunk
        for (patternLen in 12..40) {
            if (recent.length >= patternLen * 4) {
                val p = recent.takeLast(patternLen)
                val p2 = recent.substring(recent.length - patternLen * 2, recent.length - patternLen)
                val p3 = recent.substring(recent.length - patternLen * 3, recent.length - patternLen * 2)
                val p4 = recent.substring(recent.length - patternLen * 4, recent.length - patternLen * 3)
                if (p == p2 && p2 == p3 && p3 == p4) {
                    return true
                }
            }
        }
        return false
    }
}
