package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.model.ContextBudgetDefaults
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 模型档案的统一写入入口：负责 secretRef 生成、Key 持久化、活跃档案维护。
 * Settings / Chat / Onboarding 的模型保存与删除都应经由本类，避免各处自行拼装实体。
 */
@Singleton
class AiProfileWriter @Inject constructor(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
) {

    companion object {
        /**
         * 模型档案 contextTokens 的可接受区间。
         *
         * 真相源为 [ContextBudgetDefaults]（core:model），与 harness 的
         * ContextWindowPolicy.MIN/MAX_CONTEXT_BUDGET 同源同值：引擎侧 resolveEffectiveBudget
         * 用同一区间钳制，保证「填多少 / 存多少 / 显示多少 / 按多少折叠」四处闭环。
         * 历史上此处曾因 tools 不依赖 harness 而镜像定义，现已收敛到 core:model。
         */
        const val MIN_CONTEXT_TOKENS = ContextBudgetDefaults.MIN_TOKENS
        const val MAX_CONTEXT_TOKENS = ContextBudgetDefaults.MAX_TOKENS

        /** 与 ContextWindowPolicy.resolveEffectiveBudget 同语义的写入侧规范化。 */
        fun normalizeContextTokens(value: Int?): Int? =
            value?.let(ContextBudgetDefaults::normalize)
    }

    /** 解析多行 Key 文本为去重的 Key 列表 */
    fun parseApiKeys(raw: String): List<String> = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    data class UpsertRequest(
        val id: String? = null,
        val name: String,
        val provider: String,
        /** 单模型或逗号分隔的多模型字符串 */
        val model: String,
        val baseUrl: String,
        /** 多行 Key 文本；为空时保留该档案已有的 Key */
        val apiKey: String = "",
        val requestsPerMinutePerKey: Int = 0,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topP: Float? = null,
        val reasoningMode: String? = null,
        val reasoningEffort: String? = null,
        val toolCallMode: String? = null,
        val contextTokens: Int? = null,
        /**
         * 单次输入上限（token，裁切基准）：每轮请求主动裁切到的目标水位。
         * null = 未显式配置，由引擎按 `窗口 × 50%`（上限 12.8 万）推导。
         */
        val inputTokenLimit: Int? = null,
        /** 每模型压缩预算覆盖：压缩触发时保留的最近 token 上限（null = 不启用）。 */
        val compactionKeepRecentTokens: Int? = null,
        /** 每模型压缩预算覆盖：为 LLM 响应预留的 token（null = 内置默认）。 */
        val compactionReserveTokens: Int? = null,
        val customHeaders: String = "",
        val pureChatMode: Boolean = false,
        val visionEnabled: Boolean = true,
        val imageGenerationEnabled: Boolean = false,
        val responseApiEnabled: Boolean = false,
    )

    suspend fun upsertProfile(request: UpsertRequest) {
        val existing = aiModelDao.observeAll().first()
        val old = request.id?.let { aiModelDao.findById(it) }
        val modelId = request.id ?: UUID.randomUUID().toString()
        val secretRef = old?.secretRef?.takeIf { it.isNotBlank() } ?: "model_${modelId.replace("-", "")}"
        val submittedKeys = parseApiKeys(request.apiKey)
        val existingKeys = old?.let { providerRepository.readModelApiKeys(secretRef) }.orEmpty()
        // 没有任何活跃档案，或正在编辑当前活跃档案时，先清空活跃标记再写入
        if (existing.none { it.isActive } || old?.isActive == true) aiModelDao.clearActive()
        aiModelDao.upsert(
            AiModelEntity(
                id = modelId,
                name = request.name.trim(),
                provider = request.provider.trim(),
                model = request.model.trim(),
                baseUrl = request.baseUrl.trim(),
                secretRef = secretRef,
                isActive = old?.isActive ?: existing.none { it.isActive },
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                topP = request.topP,
                reasoningMode = request.reasoningMode?.ifBlank { null },
                reasoningEffort = request.reasoningEffort?.ifBlank { null },
                toolCallMode = request.toolCallMode?.ifBlank { null },
                // 写入侧规范化：此前原样落库，用户填 0 或 999999999 会出现在模型档案卡片上
                // （「0k / 999999k 上下文」），与引擎 resolveEffectiveBudget 的实际取值不一致。
                contextTokens = normalizeContextTokens(request.contextTokens),
                // 写入侧同样规范化输入上限，保证「填多少/存多少/按多少裁切」闭环。
                inputTokenLimit = request.inputTokenLimit?.let(ContextBudgetDefaults::normalizeInputLimit),
                compactionKeepRecentTokens = request.compactionKeepRecentTokens,
                compactionReserveTokens = request.compactionReserveTokens,
                customHeaders = request.customHeaders.trim(),
                pureChatMode = request.pureChatMode,
                visionEnabled = request.visionEnabled,
                imageGenerationEnabled = request.imageGenerationEnabled,
                responseApiEnabled = request.responseApiEnabled,
                apiKeyCount = submittedKeys.ifEmpty { existingKeys }.size,
                requestsPerMinutePerKey = request.requestsPerMinutePerKey.coerceAtLeast(0),
            ),
        )
        if (submittedKeys.isNotEmpty()) providerRepository.setModelApiKeys(secretRef, submittedKeys)
    }

    suspend fun deleteProfile(id: String) {
        aiModelDao.findById(id)?.secretRef?.takeIf { it.isNotBlank() }?.let { providerRepository.removeModelApiKey(it) }
        aiModelDao.delete(id)
    }
}
