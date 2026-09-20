package top.wkbin.taixu.harness.compaction

import java.io.File
import java.util.logging.Logger
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/**
 * 压缩/截断前把**被移除的原文**落盘到工作区 `.taixu-context/`，供 agent 事后检索回捞。
 *
 * 设计来源（OMP 范式，见 /workspace/omp-clone `docs/session.md`、`docs/compaction.md`）：
 * OMP 的会话日志是 append-only，压缩只追加一条边界记录，被压掉的原文仍在文件里，
 * 因此 agent 可以随时用 `history://` / 读 JSONL 找回。太墟此前只把旧内容换成语义摘要，
 * 摘要里不给「原文在哪」——等于一次性丢弃，这是本次要根治的短板。
 *
 * 本类刻意做成**无状态工具对象**（而不是往 CompactionManager 注入新依赖）：
 *  - 只依赖文件系统与消息模型，不给 DI 图增加节点，测试可直接调用；
 *  - 失败一律吞掉并返回 null —— 归档是「锦上添花」，绝不能因为它失败而中断压缩主流程。
 *
 * 落盘格式刻意用**纯文本 Markdown**（而非 JSON）：agent 用 `read` / `grep` 即可命中，
 * 不需要先解析结构；每条消息用 `## [序号] 类型` 标题分隔，便于 grep 定位。
 */
object ContextArchive {

    /**
     * 刻意使用 `java.util.logging` 而非 `android.util.Log`：本类是**纯工具对象**，
     * 不依赖 Android 运行时才能被纯 JVM 单元测试真实覆盖（Robolectric 在本仓 aarch64
     * 沙箱受 conscrypt 限制无法运行）。Android 上 JUL 同样会汇入 logcat。
     */
    private val logger: Logger = Logger.getLogger("ContextArchive")

    /** 归档根目录名（工作区根下）。放在工作区内，agent 的 read/base 工具天然可访问。 */
    const val DIR_NAME = ".taixu-context"

    /**
     * 单会话归档保留的**最近文件数上限**：超出则删除最旧的归档文件。
     * 防止长会话反复压缩导致 `.taixu-context/` 无限累积（这正是本任务要治的「无节制积累」）。
     */
    private const val MAX_ARCHIVES_PER_SESSION = 20

    /**
     * 单条消息写入归档时的**最大字符数**：超出则截断并标注省略量。
     * 防止一次超长工具输出（几十万字符）把归档文件撑爆、拖慢 read/grep。
     */
    private const val MAX_MESSAGE_CHARS = 20_000

    /**
     * 把 [messages] 原文写入 `<workspace>/.taixu-context/<sessionId>/<时间戳>-<序号>-<随机>.md`。
     *
     * @param workspacePath 工作区绝对路径；为空/不存在时**不落盘**，返回 null（不报错）。
     * @param sessionId 会话 id，用作子目录名（同时便于归属排查）。
     * @param messages 被折叠/截断掉的原始消息（按时间顺序）。
     * @param reason 触发归档的原因文案（写进文件头，便于人工排查）。
     * @return 成功时返回**工作区相对路径**（如 `.taixu-context/abc/1726-1.md`），供摘要索引用；
     *   失败返回 null。
     */
    /**
     * 删除某会话的归档目录 `<workspace>/.taixu-context/<sessionId>/`。
     *
     * 为什么需要它：`trim` 只做**单会话内**的体积控制（保留最近 20 个文件），
     * 而**会话级目录本身**从来没人删。会话 id 是 UUID 且不复用 → 每删一个会话，
     * 它的归档目录就永久留在**用户可见的工作区**里。
     * 累积效应比纯数据库孤儿更刺眼：用户在自己的工程目录里看到一堆
     * `.taixu-context/<uuid>/`，且 `read`/`grep`/`ls` 都会被这些无关文件干扰。
     *
     * 同族对照：`CheckpointStore.delete(sessionId)` 早就做了 `File(root, sessionId).deleteRecursively()`，
     * 归档侧缺失同一动作。
     *
     * 纯尽力而为：失败吞掉（与 [archive] 的容错一致），会话删除不应因清理归档失败而中断。
     *
     * @return 是否真的删除了目录（不存在或失败均返回 false）
     */
    fun deleteSessionArchive(workspacePath: String?, sessionId: String): Boolean {
        if (workspacePath.isNullOrBlank() || sessionId.isBlank()) return false
        return try {
            val dir = File(File(workspacePath, DIR_NAME), safeSessionId(sessionId))
            if (!dir.exists()) return false
            val deleted = dir.deleteRecursively()
            if (deleted) logger.info("已删除会话归档目录：${dir.absolutePath}")
            deleted
        } catch (throwable: Throwable) {
            logger.warning("删除会话归档目录失败（不影响会话删除）：${throwable.message}")
            false
        }
    }

    /**
     * 会话 id → 目录名。**必须与 [archive] 用同一套规则**：
     * 两处若不一致，删除会指向一个不存在的目录、静默 no-op（归档越积越多却"看起来已经清了"）。
     */
    internal fun safeSessionId(sessionId: String): String =
        sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "session" }

    fun archive(
        workspacePath: String?,
        sessionId: String,
        messages: List<HarnessMessage>,
        reason: String,
        archiveId: String,
    ): String? {
        if (workspacePath.isNullOrBlank() || messages.isEmpty()) return null
        return try {
            val safeSession = safeSessionId(sessionId)
            val dir = File(File(workspacePath, DIR_NAME), safeSession)
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warning("归档目录创建失败：${dir.absolutePath}")
                return null
            }
            val file = File(dir, "$archiveId.md")
            file.writeText(render(messages, reason, archiveId))
            val relative = "$DIR_NAME/$safeSession/$archiveId.md"
            logger.info("已归档 ${messages.size} 条原文 → $relative")
            // 保留最近 N 个归档，清掉更旧的，避免目录无限膨胀（归档是留档，不是唯一副本）。
            trim(dir)
            relative
        } catch (throwable: Throwable) {
            // 归档失败不能影响压缩主流程（磁盘满 / 权限异常等）。
            logger.warning("原文归档失败（不影响压缩）：${throwable.message}")
            null
        }
    }

    /**
     * 生成附在压缩摘要末尾的「原文索引」段落。
     *
     * 目的：让模型知道「这部分历史没丢，原文在某文件」，需要细节时用 read / grep 回捞，
     * 而不是凭摘要臆测。返回空串表示无索引可附（未归档）。
     */
    fun indexNote(relativePath: String?, messageCount: Int): String {
        if (relativePath.isNullOrBlank() || messageCount <= 0) return ""
        return buildString {
            append("\n\n---\n")
            append("【被压缩原文索引】本摘要覆盖的 $messageCount 条原始消息已完整归档在：\n")
            append("- `$relativePath`（纯文本 Markdown，含原始 user / assistant / tool 内容）\n")
            append("若需要被折叠部分的精确细节（原始的报错、文件内容、命令输出），")
            append("请用 `read` 打开该文件，或 `grep`/`rg` 在其中检索关键词，不要凭本摘要臆测。")
            append("\n\n【更早历史的检索入口】归档文件仅滚动保留最近若干份，更早批次可能已被回收；")
            append("但全部历史条目永久保存在会话树中。需要摘要之外的任何历史细节时，")
            append("请直接用会话检索工具回捞（无需依赖归档文件是否存在）：\n")
            append("- `history_search(query=\"关键词\")`：在当前会话完整历史中按关键词定位旧消息；\n")
            append("- `history_read(message_id=\"...\")`：用上一步返回的 id 读取该条原文。\n")
            append("凡涉及被压缩的历史细节，一律先 `history_search` 再下结论，不要凭摘要记忆臆测。")
        }
    }

    /** 生成稳定的归档文件名（时间戳 + 会话内递增序号），避免并发/连续压缩互相覆盖。 */
    fun archiveId(createdAt: Long, sequence: Int): String =
        "$createdAt-$sequence-${java.util.UUID.randomUUID().toString().substring(0, 8)}"

    /**
     * 无归档时的兜底索引：未启用归档 / 归档失败时，仍给出「更早历史检索入口」，
     * 保证压缩摘要永远带得动回捞路径，不因归档缺失而丢掉检索能力。
     */
    fun searchEntryNote(messageCount: Int): String {
        if (messageCount <= 0) return ""
        return buildString {
            append("\n\n---\n")
            append("【历史检索入口】本摘要覆盖了约 $messageCount 条更早的原始消息（未归档，仅存于会话树）。")
            append("需要这份摘要之外的任何历史细节时，请用会话检索工具回捞，不要凭摘要记忆臆测：\n")
            append("- `history_search(query=\"关键词\")`：在当前会话完整历史中按关键词定位旧消息；\n")
            append("- `history_read(message_id=\"...\")`：用上一步返回的 id 读取该条原文。")
        }
    }

    /**
     * 只保留最近 [MAX_ARCHIVES_PER_SESSION] 个归档文件，按文件名（时间戳前缀）排序删除更旧的。
     * 纯尽力而为：任何异常都吞掉，绝不影响压缩主流程。
     */
    private fun trim(sessionDir: File) {
        runCatching {
            val files = sessionDir.listFiles { file -> file.isFile && file.name.endsWith(".md") }
                ?: return
            if (files.size <= MAX_ARCHIVES_PER_SESSION) return
            files.sortedBy { it.name }
                .dropLast(MAX_ARCHIVES_PER_SESSION)
                .forEach { it.delete() }
        }
    }

    /**
     * 截断超长文本为「头部 + 省略提示」。用**码点**边界切割，避免把代理对（emoji 等）劈成半个字符。
     */
    private fun truncate(text: String): String {
        if (text.length <= MAX_MESSAGE_CHARS) return text
        val cut = text.offsetByCodePoints(0, MAX_MESSAGE_CHARS)
        return text.substring(0, cut) +
            "\n…（原文过长，此处省略 ${text.length - cut} 字符；完整内容见会话原始记录）"
    }

    private fun render(messages: List<HarnessMessage>, reason: String, archiveId: String): String = buildString {
        append("# 压缩原文归档 $archiveId\n\n")
        append("- 原因：$reason\n")
        append("- 条数：${messages.size}\n")
        append("- 说明：以下为被压缩出上下文的原始消息，仅作检索留档，不再参与请求组装。\n\n")
        messages.forEachIndexed { index, message ->
            append("## [${index + 1}] ${messageTypeName(message)}\n\n")
            append(truncate(messageText(message)))
            append("\n\n")
        }
    }

    private fun messageTypeName(message: HarnessMessage): String = when (message) {
        is UserMessage -> "user"
        is AssistantText -> "assistant"
        is ToolCall -> "tool_call"
        is ToolResult -> "tool_result"
        else -> message::class.simpleName ?: "message"
    }

    /**
     * 还原消息的**可读原文**。刻意不直接用 `toString()`（数据类 toString 会把转义换行符
     * 原样写出，长 JSON 挤成一行，grep 体验极差）。
     */
    private fun messageText(message: HarnessMessage): String = when (message) {
        is UserMessage -> buildString {
            append(message.text)
            if (message.imageUrls.isNotEmpty()) append("\n[图片 ${message.imageUrls.size} 张]")
        }
        is AssistantText -> buildString {
            append(message.text)
            message.reasoning?.takeIf { it.isNotBlank() }?.let { append("\n\n[reasoning]\n").append(it) }
        }
        is ToolCall -> buildString {
            append("工具：${message.rawToolName ?: message.tool}\n")
            append("参数：${message.args}")
            message.reasoning?.takeIf { it.isNotBlank() }?.let { append("\n\n[reasoning]\n").append(it) }
        }
        is ToolResult -> "工具：${message.toolCallId}\n输出：\n${message.output}"
        else -> message.toString()
    }
}
