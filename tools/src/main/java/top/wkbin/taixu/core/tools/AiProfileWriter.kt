package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
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
         * 必须与 harness 模块 ContextWindowPolicy 的 MIN_CONTEXT_BUDGET / MAX_CONTEXT_BUDGET
         * **保持同值**：tools 模块不依赖 harness，无法直接引用，故此处镜像定义。
         * 引擎侧 resolveEffectiveBudget 用同区间钳制，两处一致才能保证
         * 「填多少 / 存多少 / 显示多少 / 按多少折叠」四处闭环。
         */
        const val MIN_CONTEXT_TOKENS = 4_000
        const val MAX_CONTEXT_TOKENS = 2_000_000

        /** 与 ContextWindowPolicy.resolveEffectiveBudget 同语义的写入侧规范化。 */
        fun normalizeContextTokens(value: Int?): Int? =
            value?.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
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
