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
    return messages
        .mapIndexedNotNull { index, message ->
            if (message is ToolResult) null else ChatRenderItem.MessageItem(message, rawIndex = index)
        }
}
