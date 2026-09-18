package top.wkbin.taixu.harness.compaction

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/**
 * 会话文本序列化（对齐 pi 的 serializeConversation）：
 * 把 HarnessMessage 列表压平为 `[User]:` / `[Assistant]:` / `[Assistant tool calls]:` / `[Tool result]:`
 * 的纯文本叙事，供 LLM 摘要请求使用。纯文本形态防止模型把历史当作"要继续的对话"。
 *
 * 设计要点（源自 pi compaction/utils.ts）：
 * - 工具结果截断到 [TOOL_RESULT_CHAR_LIMIT]（2000 字符），超出部分标注省略量——
 *   工具输出（read/bash）是上下文体积的最大来源，摘要请求必须控制预算；
 * - 连续多个 ToolCall 合并为一行（`read(path="a"); edit(path="b")`）；
 * - UI-only 事件（能力激活/模型切换）不参与摘要。
 */
object ConversationText {

    const val TOOL_RESULT_CHAR_LIMIT = 2_000
    private const val ARG_VALUE_CHAR_LIMIT = 240
    private const val THINKING_CHAR_LIMIT = 1_200
    /** 序列化文本总量上限（字符）：超出时保头尾，避免摘要请求本身爆上下文。 */
    const val MAX_SERIALIZED_CHARS = 240_000

    fun toolCallDetailsOf(messages: List<HarnessMessage>): Map<String, Pair<String, JsonObject>> =
        messages.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }

    fun serialize(
        messages: List<HarnessMessage>,
        toolCallDetails: Map<String, Pair<String, JsonObject>> = toolCallDetailsOf(messages),
    ): String {
        val lines = mutableListOf<String>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message) {
                is CapabilityEvent, is ModelSwitchEvent -> Unit
                is UserMessage -> lines += "[User]: ${message.text.trim()}"
                is AssistantText -> {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let {
                        lines += "[Assistant thinking]: ${it.trim().take(THINKING_CHAR_LIMIT)}"
                    }
                    if (message.text.isNotBlank()) lines += "[Assistant]: ${message.text.trim()}"
                }
                is ToolCall -> {
                    // 连续 ToolCall 合并为一行，模拟真实 assistant 消息的并行工具调用形态
                    val calls = mutableListOf<String>()
                    var cursor = index
                    while (cursor < messages.size && messages[cursor] is ToolCall) {
                        calls += formatToolCall(messages[cursor] as ToolCall, toolCallDetails)
                        cursor++
                    }
                    lines += "[Assistant tool calls]: ${calls.joinToString("; ")}"
                    index = cursor
                    continue
                }
                is ToolResult -> {
                    val name = toolCallDetails[message.toolCallId]?.first ?: "tool"
                    lines += "[Tool result]($name): ${truncateToolResult(message.output)}"
                }
            }
            index++
        }
        val text = lines.joinToString("\n")
        return if (text.length <= MAX_SERIALIZED_CHARS) text else clipSerialized(text)
    }

    /** 超长时保留首部（初始目标）与尾部（最新进展），中段标注省略量。 */
    private fun clipSerialized(text: String): String {
        val headBudget = MAX_SERIALIZED_CHARS / 6
        val tailBudget = MAX_SERIALIZED_CHARS - headBudget
        val omitted = text.length - headBudget - tailBudget
        return text.take(headBudget) +
            "\n…[中段 $omitted 字符已省略]…\n" +
            text.takeLast(tailBudget)
    }

    private fun formatToolCall(call: ToolCall, details: Map<String, Pair<String, JsonObject>>): String {
        val name = details[call.id]?.first ?: call.rawToolName ?: HarnessApiMapper.apiName(call.tool)
        val args = call.args.entries.joinToString(", ") { (key, value) ->
            val rendered = runCatching {
                when {
                    value is kotlinx.serialization.json.JsonPrimitive -> value.jsonPrimitive.contentOrNull.orEmpty()
                    else -> value.toString()
                }
            }.getOrDefault(value.toString())
            "$key=\"${rendered.take(ARG_VALUE_CHAR_LIMIT)}\""
        }
        return "$name(${args})"
    }

    private fun truncateToolResult(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= TOOL_RESULT_CHAR_LIMIT) return trimmed
        return trimmed.take(TOOL_RESULT_CHAR_LIMIT) +
            "…[已截断，省略 ${trimmed.length - TOOL_RESULT_CHAR_LIMIT} 字符]"
    }
}

/** 从消息中提取的累计文件操作（对齐 pi 的 readFiles/modifiedFiles 累计追踪）。 */
data class FileOperations(
    val readFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
) {
    fun mergedWith(other: FileOperations): FileOperations = FileOperations(
        readFiles = (readFiles + other.readFiles).distinct(),
        modifiedFiles = (modifiedFiles + other.modifiedFiles).distinct(),
    )

    fun renderTags(): String = buildString {
        if (readFiles.isNotEmpty()) {
            appendLine("<read-files>")
            readFiles.forEach { appendLine(it) }
            appendLine("</read-files>")
        }
        if (modifiedFiles.isNotEmpty()) {
            appendLine("<modified-files>")
            modifiedFiles.forEach { appendLine(it) }
            appendLine("</modified-files>")
        }
    }.trim()

    companion object {
        private val READ_TOOLS = setOf("read", "download")
        private val MODIFY_TOOLS = setOf("write", "edit")

        /** 从被摘要消息的工具调用中提取文件操作（read/download → 读；write/edit → 改）。 */
        fun extractFrom(messages: List<HarnessMessage>): FileOperations {
            val reads = mutableListOf<String>()
            val modifies = mutableListOf<String>()
            messages.filterIsInstance<ToolCall>().forEach { call ->
                val name = (call.rawToolName ?: HarnessApiMapper.apiName(call.tool)).lowercase()
                val path = runCatching {
                    call.args["path"]?.jsonPrimitive?.contentOrNull
                        ?: call.args["file"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim().orEmpty()
                if (path.isEmpty()) return@forEach
                when {
                    name in READ_TOOLS -> reads += path
                    name in MODIFY_TOOLS -> modifies += path
                }
            }
            return FileOperations(reads.distinct(), modifies.distinct())
        }

        private val TAG_BLOCK = Regex(
            "<(read-files|modified-files)>\\s*([\\s\\S]*?)\\s*</\\1>",
        )

        /** 从上一份结构化摘要中解析既有文件清单，实现跨压缩累计。 */
        fun parseFromSummary(summary: String?): FileOperations {
            if (summary.isNullOrBlank()) return FileOperations()
            var reads = emptyList<String>()
            var modifies = emptyList<String>()
            TAG_BLOCK.findAll(summary).forEach { match ->
                val paths = match.groupValues[2].lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                when (match.groupValues[1]) {
                    "read-files" -> reads = paths
                    "modified-files" -> modifies = paths
                }
            }
            return FileOperations(reads, modifies)
        }
    }
}

/**
 * LLM 结构化压缩摘要器（对齐 pi compaction.ts 的 generateSummary）。
 *
 * 与机械摘要（[top.wkbin.taixu.harness.ContextWindowPolicy.buildHistorySummary]）的关系：
 * 本类生成 pi 风格的结构化摘要（目标/约束/进度/关键决策/下一步/关键上下文 + 文件清单），
 * 上一次摘要作为迭代上下文传入，天然实现滚动合并；任何失败（网络/解析/空输出）返回 null，
 * 由调用方回退到机械摘要，保证压缩永不因摘要失败而中断。
 */
@Singleton
class CompactionSummarizer @Inject constructor(
    private val providerClient: ProviderClient,
) {
    /** 结构化摘要提示词（对齐 pi 的 Summary Format）。 */
    internal fun buildPrompt(serialized: String, previousSummaries: List<String>): String {
        val previous = previousSummaries
            .filter { it.isNotBlank() }
            .joinToString("\n\n---\n\n")
            .takeIf { it.isNotEmpty() }
        return buildString {
            appendLine("你是会话压缩器。请把下面的对话历史压缩为一份结构化摘要，供同一会话的后续轮次继续使用。")
            appendLine("摘要将被注入后续请求作为早期历史的唯一替代，必须自包含。")
            appendLine()
            appendLine("输出格式（严格遵守，只输出摘要本身，不要任何寒暄或代码块包裹）：")
            appendLine("## 目标")
            appendLine("[用户想完成什么]")
            appendLine("## 约束与偏好")
            appendLine("- [用户明确提出的要求、偏好与禁区]")
            appendLine("## 进度")
            appendLine("### 已完成")
            appendLine("- [x] [已完成的任务]")
            appendLine("### 进行中")
            appendLine("- [ ] [当前正在做的事]")
            appendLine("### 受阻")
            appendLine("- [问题与原因，无则省略该小节]")
            appendLine("## 关键决策")
            appendLine("- **[决策]**：[理由]")
            appendLine("## 下一步")
            appendLine("1. [接下来应该做什么]")
            appendLine("## 关键上下文")
            appendLine("- [继续工作必需的数据：路径、命令输出要点、错误信息、版本号等]")
            if (previous != null) {
                appendLine()
                appendLine("【此前的压缩摘要】（请把其中的关键信息合并进新摘要，不要丢失未完成事项与决策）：")
                appendLine(previous)
            }
            appendLine()
            appendLine("【待压缩的对话历史】：")
            appendLine(serialized)
        }
    }

    /**
     * 生成结构化摘要；失败返回 null（调用方回退机械摘要）。
     * 文件操作清单不依赖模型自觉：从工具调用确定性提取，并与上一份摘要累计后追加到末尾。
     */
    suspend fun generateSummary(
        model: ModelConfig,
        messages: List<HarnessMessage>,
        previousSummaries: List<String> = emptyList(),
    ): String? {
        if (messages.isEmpty()) return null
        val serialized = ConversationText.serialize(messages)
        if (serialized.isBlank()) return null
        val prompt = buildPrompt(serialized, previousSummaries)
        val summaryModel = model.copy(
            // 摘要是一次性请求：输出上限固定为温和值，不透传主对话可能配置的极小 maxTokens
            maxTokens = minOf(model.maxTokens ?: DEFAULT_SUMMARY_MAX_TOKENS, DEFAULT_SUMMARY_MAX_TOKENS),
            pureChatMode = false,
        )
        val result = runCatching {
            providerClient.chat(
                summaryModel,
                listOf(
                    ApiMessage(role = "system", content = SYSTEM_PROMPT),
                    ApiMessage(role = "user", content = prompt),
                ),
            )
        }.getOrNull() ?: return null
        val body = result.content?.trim().orEmpty()
        if (body.length < MIN_SUMMARY_CHARS) return null
        val files = FileOperations.parseFromSummary(previousSummaries.joinToString("\n\n"))
            .mergedWith(FileOperations.extractFrom(messages))
        val fileTags = files.renderTags()
        return if (fileTags.isEmpty()) body else "$body\n\n$fileTags"
    }

    private companion object {
        const val DEFAULT_SUMMARY_MAX_TOKENS = 4_096
        const val MIN_SUMMARY_CHARS = 60
        const val SYSTEM_PROMPT =
            "你是会话压缩器。任务：把长对话历史压缩为结构化摘要。只输出摘要正文，" +
                "保留全部关键信息（目标、约束、进度、决策、下一步、关键上下文），不要复述无关细节。"
    }
}
