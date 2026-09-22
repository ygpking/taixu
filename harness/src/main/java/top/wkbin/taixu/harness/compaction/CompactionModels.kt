package top.wkbin.taixu.harness.compaction

import kotlinx.serialization.Serializable
import top.wkbin.taixu.harness.HarnessMessage

@Serializable
data class CompactionPayload(
    val sourceLeafId: String?,
    val summary: String,
    val retainedMessagesJson: String,
    val compactedMessageCount: Int,
    /** Cumulative folded count, added later for O(1) snapshots; null on legacy payloads. */
    val cumulativeCompactedMessageCount: Int? = null,
    val retainedMessageCount: Int,
    val estimatedTokensBefore: Int,
    val createdAt: Long,
)

data class CompactedContext(
    val summary: String? = null,
    val messages: List<HarnessMessage> = emptyList(),
    /**
     * 投影发生时的 lane 叶子 id。
     *
     * compact() 用它做落库前的稳定性复核：压缩主干是一次数秒的 LLM 调用，期间并发写 lane
     * 会让叶子前进，而 appendToLane 对陈旧 parentId 是静默 rebase——窗口内写入的消息会
     * 永久退出投影。不能从 messages 末条推导：分支摘要等非 message 条目不进 messages
     * 却会推进叶子。
     */
    val sourceLeafId: String? = null,
    /**
     * 活跃分支上、最近一次压缩之后的分支摘要（对齐 pi 的 BranchSummaryEntry）。
     * 按树序排列；在 compact() 时会被折叠进新的压缩摘要，不会重复注入。
     */
    val branchSummaries: List<String> = emptyList(),
) {
    /** 注入 provider 请求的完整摘要层（压缩摘要 + 分支摘要）。 */
    val summaryLayer: String
        get() = (listOfNotNull(summary) + branchSummaries)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
}

/**
 * 最近一次压缩的只读快照，供 UI 展示折叠透明度信息：
 * 被折叠进摘要的早期消息条数与摘要文本预览。
 */
data class CompactionSnapshot(
    val summary: String,
    /** 累计被折叠进摘要的早期消息条数（多次压缩会累加）。 */
    val foldedMessageCount: Int,
    val createdAt: Long,
)

/** 分支摘要树节点负载：切换分支时对被放弃分支生成、注入新位置的上下文（对齐 pi BranchSummaryEntry）。 */
@Serializable
data class BranchSummaryPayload(
    val summary: String,
    /** 切换前的旧叶子（被放弃分支的末端）。 */
    val fromLeafId: String?,
    /** 被摘要的消息条数。 */
    val summarizedMessageCount: Int,
    val createdAt: Long,
)
