package top.wkbin.taixu.harness.fixtures

import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ChatResult
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.skill.AdvisorModelClient

/**
 * [AdvisorModelClient] 的可编程 fake：预设模型解析结果、chat 返回值或抛出的异常。
 *
 * 用途：给顾问的触发点/冷却/生命周期写回归测试——此前这些链路零覆盖，
 * "一次失败分析烧掉 30 分钟冷却"这类缺陷因此长期存在。
 */
class FakeAdvisorModelClient(
    private var model: ModelConfig? = null,
) : AdvisorModelClient {

    var chatCalls = 0
        private set

    var lastMessages: List<ApiMessage>? = null
        private set

    /** chat 的返回体；抛异常则用 [failWith]。 */
    var chatResult: ChatResult = ChatResult(content = "{}", toolCalls = emptyList())

    var failWith: Throwable? = null

    /** 模型解析抛异常时用（模拟会话已删/模型不可用）。 */
    var resolveThrows: Throwable? = null

    fun setModel(value: ModelConfig?) {
        model = value
    }

    override suspend fun resolveConfigured(modelId: String?, modelVariant: String?): ModelConfig {
        resolveThrows?.let { throw it }
        return model ?: throw IllegalStateException("no model configured")
    }

    override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult {
        chatCalls++
        lastMessages = messages
        failWith?.let { throw it }
        return chatResult
    }
}
