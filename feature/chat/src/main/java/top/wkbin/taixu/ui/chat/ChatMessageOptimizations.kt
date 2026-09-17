package top.wkbin.taixu.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyListState
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.ToolCall

/**
 * UI 性能优化：消息列表派生状态计算
 * 
 * 问题：ChatScreen.kt 中每次流式输出新 token 时，整个 messages 列表引用变化，
 * 导致 O(n) 派生计算（如 toolResults 映射、lastAssistantMessageId）每帧重算。
 * 
 * 解决方案：使用 derivedStateOf 包装计算逻辑，仅当真正影响结果的依赖变化时才重算。
 */

/**
 * 优化的工具结果映射构建器
 * 
 * @param messages 消息列表
 * @return 工具调用 ID 到 ToolResult 的映射
 */
@Composable
fun rememberToolResultsMap(messages: List<HarnessMessage>): Map<String, ToolResult> {
    // 关键优化：不依赖 messages 整个 list 引用，而是依赖：
    // 1. ToolResult 的数量（新增/删除才会变）
    // 2. 末条 ToolResult 的输出长度（内容更新才会变）
    val toolResultCount = messages.count { it is ToolResult }
    val lastToolResultOutputLength = messages.lastOrNull { it is ToolResult }
        ?.let { (it as ToolResult).output.length } ?: 0
    
    return remember(toolResultCount, lastToolResultOutputLength) {
        messages.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    }
}

/**
 * 优化的最后一条助手消息 ID 获取
 * 
 * @param messages 消息列表
 * @return 最后一条 AssistantText 消息的 ID
 */
@Composable
fun rememberLastAssistantMessageId(messages: List<HarnessMessage>): String? {
    // 关键优化：仅当 AssistantText 数量变化或末条 ID 变化时才重算
    val assistantCount = messages.count { it is AssistantText }
    val lastAssistantId = messages.lastOrNull { it is AssistantText }?.id
    
    return remember(assistantCount, lastAssistantId) {
        messages.filterIsInstance<AssistantText>().lastOrNull()?.id
    }
}

/**
 * 优化的消息签名计算（用于检测消息内容变化）
 * 
 * @param messages 消息列表
 * @return 消息签名字符串
 */
@Composable
fun rememberLastMessageSignature(messages: List<HarnessMessage>): String {
    val last = messages.lastOrNull()
    
    // 使用 derivedStateOf 包装复杂计算，避免每帧重算
    val signature by remember(messages) {
        derivedStateOf {
            when (last) {
                is AssistantText -> "${last.id}:${last.reasoning?.length ?: 0}:${last.text.length}"
                is ToolCall -> "${last.id}:${last.args.hashCode()}"
                is ToolResult -> "${last.id}:${last.output.length}"
                is UserMessage -> "${last.id}:${last.text.length}"
                else -> "${messages.size}"
            }
        }
    }
    
    return signature
}

/**
 * 优化的 LazyListState 滚动位置跟踪
 * 
 * 问题：流式输出时高频触发 scrollToItem 导致闪烁
 * 解决：仅在用户已在底部附近时跟随滚动，上滑阅读历史时不打扰
 */
@Composable
fun rememberSmartScrollTrigger(
    listState: LazyListState,
    messagesSize: Int,
    lastMessageSignature: String,
    stickThrottleMs: Long = 240L
): Boolean {
    val lastStickAtMs = remember { LongArray(1) }
    val trackedMessageCount = remember { IntArray(1) }
    
    val shouldStick by derivedStateOf {
        val sessionSwitched = false // 由调用方传入会话键判断
        val countChanged = messagesSize != trackedMessageCount[0]
        trackedMessageCount[0] = messagesSize
        
        if (!sessionSwitched && !countChanged) {
            val now = System.currentTimeMillis()
            if (now - lastStickAtMs[0] < stickThrottleMs) {
                return@derivedStateOf false
            }
        }
        
        // 检查是否在底部附近（最后 3 条可见）
        val layoutInfo = listState.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: -1
        
        val isNearBottom = lastVisibleIndex >= totalItems - 4
        if (isNearBottom) {
            lastStickAtMs[0] = System.currentTimeMillis()
        }
        
        isNearBottom
    }
    
    return shouldStick
}

/**
 * 渲染项列表的优化投影计算
 * 
 * @param messages 原始消息列表
 * @param toolResults 工具结果映射
 * @return 渲染项列表
 */
@Composable
fun rememberRenderItems(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>
): List<ChatRenderItem> {
    // 使用稳定的键避免不必要的重组
    val messagesHash = remember(messages) {
        messages.sumOf { it.id.hashCode().toLong() }
    }
    val toolResultsHash = remember(toolResults) {
        toolResults.entries.sumOf { (k, v) -> k.hashCode().toLong() + v.output.length.toLong() }
    }
    
    return remember(messagesHash, toolResultsHash) {
        projectChatMessages(messages, toolResults)
    }
}
