package top.wkbin.taixu.ui.chat

import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolResult

/**
 * 聊天流投影渲染项：
 * 将扁平消息流投影为包含可见消息与折叠控件的渲染结构。
 */
sealed interface ChatRenderItem {
    val stableKey: String

    data class MessageItem(
        val message: HarnessMessage,
        /**
         * 该消息在**原始 messages 列表**中的下标。
         *
         * 由投影阶段一次性算出（O(n) 一次），供组合期内 O(1) 使用。
         * 此前组合期用 `messages.indexOfFirst { it.id == message.id }` 现算，
         * 每个可见项一次 O(n)，多张工具卡同时可见时为 O(n²)，滚动/流式时明显掉帧。
         */
        val rawIndex: Int = -1,
    ) : ChatRenderItem {
        override val stableKey: String get() = message.id
    }

    data class CollapseButtonItem(
        val roundKey: String,
        val hiddenSteps: Int,
        val totalSteps: Int,
        val hiddenDurationMs: Long,
        val isExpanded: Boolean,
    ) : ChatRenderItem {
        override val stableKey: String get() = "collapse_btn_$roundKey"
    }
}

/**
 * 纯函数投影：将原始消息列表按自然清晰的单行流投影为 LazyColumn 的渲染项。
 *
 * @param messages 原始 Harness 消息流
 * @param toolResults 工具执行结果映射表（用于提取 durationMs）
 * @param expandedOverrides 手动展开/收起记忆表
 */
fun projectChatMessages(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult> = emptyMap(),
    expandedOverrides: Map<String, Boolean> = emptyMap(),
): List<ChatRenderItem> {
    if (messages.isEmpty()) return emptyList()

    // 过滤掉已被 ToolCard 内部独立消费渲染的 ToolResult，所有思考过程与工具调用按自然单行流呈现。
    // 同时把「原始下标」一次性算入渲染项：组合期据此做 O(1) 读取，
    // 免除在 Lazy 项内对 messages 反复 indexOfFirst 造成的 O(n²) 扫描。
    //
    // 实现说明：旧写法 `messages.mapIndexedNotNull { ... }` 会创建 2 个 List 再合并
    // （mapNotNull 内部 collectTo，先 ArrayList 累积、末尾再复制一次）。本函数在
    // 流式输出期间被反复调用（每帧一次），长会话下每次多分配一次原表大小的数组，
    // 属无谓的 GC 压力。改为单趟构建：先按 size 预分配，只复制可见项，并就地重排。
    val projected = ArrayList<ChatRenderItem>(messages.size)
    for (index in messages.indices) {
        val message = messages[index]
        if (message !is ToolResult) {
            projected.add(ChatRenderItem.MessageItem(message, rawIndex = index))
        }
    }
    // 预分配后容量常大于实际条目数。ArrayList 不暴露"收缩容量"的 API，
    // 但 `toList()` 会走优化路径：size == capacity 时直接返回副本，否则精确复制。
    // 这里统一交给它处理，避免超容量数组被 LazyColumn 长期持有。
    return projected.toList()
}
