package top.wkbin.taixu.harness.compaction

import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.session.SessionTreeStore

/** Persists compaction as an immutable tree entry and projects provider context from it. */
@Singleton
class CompactionManager @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val summarizer: CompactionSummarizer? = null,
) {
    suspend fun project(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactedContext {
        val lane = repository.ensureLane(sessionId, laneName)
        // Provider projection must walk the complete active branch. A UI-sized tail limit here
        // silently drops unsummarized messages before token budgeting gets a chance to compact them.
        // The latest compaction is still fetched separately to recover its retained snapshot.
        val latestCompaction = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
        val entries = repository.branch(sessionId, lane.leafId)
        if (latestCompaction == null) {
            return CompactedContext(
                messages = entries.mapNotNull(::decodeMessage),
                branchSummaries = branchSummariesWithin(entries, afterSequence = null),
                sourceLeafId = lane.leafId,
            )
        }

        val payload = json.decodeFromString(CompactionPayload.serializer(), latestCompaction.payloadJson)
        val retained = json.decodeFromString(ListSerializer(HarnessMessage.serializer()), payload.retainedMessagesJson)
        val afterMessages = entries.asSequence()
            .filter { it.sequence > latestCompaction.sequence }
            .mapNotNull(::decodeMessage)
            .toList()
        // 压缩之后的分支摘要原样注入；之前的已在 compact() 时折叠进压缩摘要，避免重复
        return CompactedContext(
            summary = payload.summary,
            messages = retained + afterMessages,
            branchSummaries = branchSummariesWithin(entries, afterSequence = latestCompaction.sequence),
            sourceLeafId = lane.leafId,
        )
    }

    /**
     * 最近一次压缩的轻量快照（不解码保留消息）。
     * 折叠条数为历次压缩累计；摘要与时间取最新一条——与 UI 横幅对齐。
     * 会话从未压缩时返回 null——UI 据此隐藏折叠提示。
     */
    suspend fun latestSnapshot(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactionSnapshot? {
        val lane = runCatching { repository.ensureLane(sessionId, laneName) }.getOrNull() ?: return null
        val latestEntry = runCatching {
            repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
        }.getOrNull() ?: return null
        val newest = runCatching {
            json.decodeFromString(CompactionPayload.serializer(), latestEntry.payloadJson)
        }.getOrNull() ?: return null
        return CompactionSnapshot(
            summary = newest.summary,
            foldedMessageCount = newest.cumulativeCompactedMessageCount ?: newest.compactedMessageCount,
            createdAt = newest.createdAt,
        )
    }

    suspend fun compact(
        sessionId: String,
        context: CompactedContext,
        keepFromIndex: Int,
        laneName: String = SessionTreeStore.MAIN_LANE,
        model: ModelConfig? = null,
        archiveEnabled: Boolean = false,
        workspacePath: String? = null,
        /**
         * 归档前的会话存在性复核。压缩的主干是一次 LLM 调用（数秒），
         * 期间用户可能已经删掉这个会话——[HarnessLoop.deleteSession] 只 join
         * 自己启动的 runLoop，viewModelScope 里的切换压缩不在其中。
         * 不复核的后果：归档目录在删除清理**之后**又被建回来（会话 id 不复用，
         * 于是永久残留），并往已删除的会话写孤儿 lane 行。
         * 调用方不传时退化为 `{ true }`，行为与从前一致。
         *
         * 注意该复核**只覆盖归档**：落库前的会话存在性与 lane 叶子稳定性由 compact 内部
         * 另行复核（见下方 `leafBeforeAppend`）。
         */
        sessionStillExists: suspend () -> Boolean = { true },
    ): CompactedContext {
        require(keepFromIndex in 1..context.messages.size) { "Compaction must remove at least one message" }
        // 期望的父叶子 = 投影快照时的 lane 叶子（CompactionContext.sourceLeafId）。
        // 落库前的稳定性复核要用它，见下方 appendToLane 前的检查。
        val expectedLeafId = context.sourceLeafId
        val lane = repository.ensureLane(sessionId, laneName)
        val collapsed = context.messages.take(keepFromIndex)
        val retained = context.messages.drop(keepFromIndex)
        // 上一份摘要层（压缩摘要 + 尚未折叠的分支摘要）作为迭代上下文传入 LLM，
        // 天然实现滚动合并；LLM 不可用时机械摘要 + 字符串拼接兜底。
        val previousSummaries = buildList {
            context.summary?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(context.branchSummaries.filter { it.isNotBlank() })
        }
        val llmSummary = if (model != null && summarizer != null && collapsed.isNotEmpty()) {
            try {
                summarizer.generateSummary(model, collapsed, previousSummaries)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(
                    "ContextCompaction",
                    "LLM 结构化摘要失败，回退机械摘要：${throwable.message}",
                )
                null
            }
        } else {
            null
        }
        val summary = if (llmSummary != null) {
            llmSummary
        } else {
            val incrementalSummary = ContextWindowPolicy.buildHistorySummary(collapsed)
            mergeRollingSummary(previousSummaries.joinToString("\n\n"), incrementalSummary)
        }
        val now = System.currentTimeMillis()
        // 先取历史累计折叠条数（用于归档文件头与 payload），再归档。
        val previousFoldedCount = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
            ?.let { entry ->
                runCatching { json.decodeFromString(CompactionPayload.serializer(), entry.payloadJson) }.getOrNull()
            }
            ?.let { it.cumulativeCompactedMessageCount ?: it.compactedMessageCount }
            ?: 0
        // 原文归档（OMP 范式）：把被折叠掉的原始消息完整写入工作区 `.taixu-context/`，
        // 并在摘要末尾附「原文索引」，让 agent 事后能用 read/grep 回捞精确细节。
        // 归档失败返回 null，不影响压缩主流程（见 ContextArchive 的容错设计）。
        val archiveId = ContextArchive.archiveId(now, collapsed.size)
        val archivedRelativePath = if (archiveEnabled && sessionStillExists()) {
            ContextArchive.archive(
                workspacePath = workspacePath,
                sessionId = sessionId,
                messages = collapsed,
                reason = "上下文压缩：本批折叠 ${collapsed.size} 条（累计 ${previousFoldedCount + collapsed.size} 条）",
                archiveId = archiveId,
            )
        } else {
            null
        }
        val summaryWithIndex = summary + (
            ContextArchive.indexNote(archivedRelativePath, collapsed.size)
                .ifBlank { ContextArchive.searchEntryNote(collapsed.size) }
            )
        val payload = CompactionPayload(
            sourceLeafId = expectedLeafId ?: lane.leafId,
            summary = summaryWithIndex,
            retainedMessagesJson = json.encodeToString(ListSerializer(HarnessMessage.serializer()), retained),
            compactedMessageCount = collapsed.size,
            cumulativeCompactedMessageCount = previousFoldedCount + collapsed.size,
            retainedMessageCount = retained.size,
            estimatedTokensBefore = context.messages.sumOf(::messageTokens),
            createdAt = now,
        )
        // 落库前复核 lane 叶子是否仍在快照点上。压缩的主干是一次数秒的 LLM 调用，
        // 期间任何并发写 lane 都会让叶子前进：`appendToLane` 对陈旧 parentId 是**静默 rebase**
        // （改写 parentId 挂到最新叶子），而 project() 用「retained 快照 + sequence 晚于压缩条目」
        // 重建上下文——窗口内写入的消息既不在 retained 也不满足 sequence 条件，从此永久消失，
        // 且无异常、无日志、UI 里还在。切换压缩跑在 viewModelScope，不进 sessionJobs、
        // 不持 lane 锁，`isSessionBusy` 看不见它，与用户随手发消息的窗口天然重叠。
        // 复核不通过就放弃本次压缩、原样返回 context：下一轮请求会重新判定并重试，
        // 代价是一次白跑的 LLM 调用，收益是消息不丢。
        // 同一道闸门也覆盖"会话已被删除"：往已删除会话 appendToLane 会经 ensureLane
        // 重建 lane 行并落下压缩条目，成为永久孤儿（被用量统计与备份扫到）。
        if (!sessionStillExists()) {
            Log.w("ContextCompaction", "放弃本次压缩：会话 $sessionId 已不存在")
            return context
        }
        val leafBeforeAppend = repository.findLane(sessionId, laneName)?.leafId
        if (expectedLeafId != null && leafBeforeAppend != null && leafBeforeAppend != expectedLeafId) {
            Log.w(
                "ContextCompaction",
                "放弃本次压缩：lane 叶子已从 $expectedLeafId 前进到 $leafBeforeAppend，" +
                    "期间有新消息写入，落库会使其永久退出投影",
            )
            return context
        }
        val entry = HarnessEntryEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = leafBeforeAppend ?: lane.leafId,
            createdAt = now,
            entryType = ENTRY_TYPE,
            customType = null,
            payloadJson = json.encodeToString(CompactionPayload.serializer(), payload),
        )
        repository.appendToLane(sessionId, laneName, entry)
        Log.d(
            "ContextCompaction",
            "压缩会话 $sessionId：折叠 ${collapsed.size} 条（累计 ${payload.cumulativeCompactedMessageCount}），" +
                "保留 ${retained.size} 条，摘要 ${summaryWithIndex.length} 字符" +
                "（${if (llmSummary != null) "LLM 结构化" else "机械回退"}），" +
                "折叠分支摘要 ${context.branchSummaries.size} 份，" +
                (archivedRelativePath?.let { "原文归档 → $it，" } ?: "未归档原文，") +
                "压缩前估算 ${payload.estimatedTokensBefore} tokens",
        )
        return CompactedContext(summaryWithIndex, retained)
    }

    private fun decodeMessage(entry: HarnessEntryEntity): HarnessMessage? =
        entry.takeIf { it.entryType == "message" }?.let {
            runCatching { json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) }.getOrNull()
        }

    /** 收集活跃分支上（指定 sequence 之后）的分支摘要文本，按树序排列。 */
    private fun branchSummariesWithin(
        entries: List<HarnessEntryEntity>,
        afterSequence: Long?,
    ): List<String> = entries.asSequence()
        .filter { it.entryType == BRANCH_SUMMARY_ENTRY_TYPE }
        .filter { afterSequence == null || it.sequence > afterSequence }
        .mapNotNull { entry ->
            runCatching {
                json.decodeFromString(BranchSummaryPayload.serializer(), entry.payloadJson).summary
            }.getOrNull()
        }
        .filter { it.isNotBlank() }
        .toList()

    private fun messageTokens(message: HarnessMessage): Int = ContextWindowPolicy.estimateTokens(message.toString())

    /** Preserve both durable early context and the newest folded state after the cap is reached. */
    private fun mergeRollingSummary(previous: String?, incremental: String): String {
        val old = previous.orEmpty().trim()
        val newest = incremental.trim()
        val combined = listOf(old, newest).filter { it.isNotBlank() }.joinToString("\n\n")
        if (combined.length <= MAX_SUMMARY_CHARS) return combined
        if (old.isBlank()) return newest.takeLast(MAX_SUMMARY_CHARS)
        if (newest.isBlank()) return old.take(MAX_SUMMARY_CHARS)

        val marker = "\n\n[较早摘要中段已省略，保留其首尾]\n\n"
        val newestBudget = minOf(newest.length, MAX_SUMMARY_CHARS / 2)
        // 预留 marker 与末尾 "\n\n" 连接符，保证拼接结果严格 <= MAX_SUMMARY_CHARS
        // （CompactionSnapshotTest 断言 summary.length <= 16_000）。
        val oldBudget = (MAX_SUMMARY_CHARS - marker.length - newestBudget - 2).coerceAtLeast(0)
        val oldHead = old.take((oldBudget + 1) / 2)
        val oldTail = old.takeLast(oldBudget / 2)
        // 最新增量同样保首 + 保尾：头部含初始目标与硬约束，尾部含最新状态与阶段结论。
        // 末尾不再统一 takeLast，否则最早的初始目标与约束会被整段丢弃。
        val newestKept = if (newest.length <= newestBudget) {
            newest
        } else {
            val gap = "\n[…]\n".length
            val head = newest.take(newestBudget / 2)
            val tail = newest.takeLast((newestBudget - head.length - gap).coerceAtLeast(0))
            head + "\n[…]\n" + tail
        }
        return oldHead + marker + oldTail + "\n\n" + newestKept
    }

    companion object {
        const val ENTRY_TYPE = "compaction"
        const val BRANCH_SUMMARY_ENTRY_TYPE = "branch_summary"
        private const val MAX_SUMMARY_CHARS = 16_000
    }
}
