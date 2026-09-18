package top.wkbin.taixu.harness.session

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.projection.LiveMessagePort

/**
 * Session-scoped model switch: persist the binding, record it in the transcript like a
 * message, and immediately re-evaluate compaction against the **new** context window.
 *
 * Occupancy UI and the next provider request must share this same budget — never the
 * model that happened to be active when the session was created.
 */
@Singleton
class SessionModelSwitcher @Inject constructor(
    private val sessionDao: HarnessSessionRepository,
    private val modelDao: AiModelRepository,
    private val settingsDataStore: AgentPreferences,
    private val compactionManager: CompactionManager,
    private val providerClient: ProviderClient? = null,
    private val messagePort: LiveMessagePort,
) {
    data class Result(
        val switched: Boolean,
        val compacted: Boolean = false,
        val foldedMessageCount: Int = 0,
        val compactionPending: Boolean = false,
        val toContextTokens: Int = 0,
    )

    suspend fun switchModel(
        sessionId: String,
        profileId: String,
        variant: String? = null,
        compactIfNeeded: Boolean = true,
    ): Result {
        if (sessionId.isBlank()) return Result(switched = false)
        val profile = modelDao.findById(profileId) ?: return Result(switched = false)
        val session = sessionDao.findById(sessionId) ?: return Result(switched = false)
        val resolvedVariant = canonicalVariant(profile, variant)
        val previousProfile = session.modelId?.let { modelDao.findById(it) }
        val previousVariant = canonicalVariant(previousProfile, session.modelVariant)
        if (previousProfile?.id == profile.id && previousVariant == resolvedVariant) {
            return Result(
                switched = false,
                toContextTokens = ContextWindowPolicy.resolveBudget(
                    profile.contextTokens,
                    defaultBudget(),
                ),
            )
        }

        sessionDao.setModelSelection(sessionId, profile.id, resolvedVariant, System.currentTimeMillis())

        // 决策口径必须与 ApiContextAssembler 一致（clampedBudget，钳到 MAX_CONTEXT_BUDGET）：
        // 否则标称窗口 > 钳制上限的档案在切换时判定"无需压缩"，下一次请求组装又按钳制预算
        // 触发压缩——压缩时机与所用模型都偏离预期。toContextTokens 仅作 UI 展示，
        // 保留标称窗口值。
        val defaultBudget = defaultBudget()
        val toBudget = ContextWindowPolicy.clampedBudget(profile.contextTokens, defaultBudget)
        val fromBudget = previousProfile?.contextTokens?.let {
            ContextWindowPolicy.resolveBudget(it, defaultBudget)
        }
        val toContextTokensDisplay = ContextWindowPolicy.resolveBudget(profile.contextTokens, defaultBudget)

        val compactionEnabled = runCatching {
            settingsDataStore.contextCompactionEnabled.first()
        }.getOrDefault(true)
        val context = compactionManager.project(sessionId)
        val systemTokens = ContextWindowPolicy.estimateReservedPromptTokens(
            pureChat = profile.pureChatMode,
            toolDisabled = profile.pureChatMode ||
                profile.toolCallMode.equals("disabled", ignoreCase = true),
            summaryTokens = ContextWindowPolicy.estimateTokens(context.summaryLayer),
        )
        val keepFrom = if (compactionEnabled) {
            ContextWindowPolicy.computeKeepFromIndex(
                context.messages,
                toBudget,
                systemTokens,
                keepRecentTokens = profile.compactionKeepRecentTokens ?: 0,
                reserveTokens = profile.compactionReserveTokens,
            )
        } else {
            0
        }

        var compacted = false
        var folded = 0
        var pending = false
        if (keepFrom > 0) {
            if (compactIfNeeded) {
                // 切换模型立即压缩：用目标模型生成 LLM 结构化摘要（解析失败回退机械摘要）
                val targetModelConfig = providerClient?.let { client ->
                    runCatching { client.resolveSavedModelProfile(profile.id, resolvedVariant) }.getOrNull()
                }
                compactionManager.compact(sessionId, context, keepFrom, model = targetModelConfig)
                compacted = true
                folded = keepFrom
            } else {
                pending = true
            }
        }

        messagePort.append(
            sessionId,
            ModelSwitchEvent(
                id = "model-switch:${UUID.randomUUID()}",
                createdAt = System.currentTimeMillis(),
                fromLabel = modelLabel(previousProfile, previousVariant),
                toLabel = modelLabel(profile, resolvedVariant),
                fromContextTokens = fromBudget,
                toContextTokens = toContextTokensDisplay,
                compacted = compacted,
                foldedMessageCount = folded,
                compactionPending = pending,
            ),
        )
        return Result(
            switched = true,
            compacted = compacted,
            foldedMessageCount = folded,
            compactionPending = pending,
            toContextTokens = toContextTokensDisplay,
        )
    }

    private suspend fun defaultBudget(): Int =
        runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(128_000)

    companion object {
        fun canonicalVariant(profile: AiModelEntity?, variant: String?): String? {
            val requested = variant?.trim()?.takeIf { it.isNotBlank() }
            if (requested != null) return requested
            return profile?.model?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
        }

        fun modelLabel(profile: AiModelEntity?, variant: String?): String {
            val name = profile?.name?.trim().orEmpty()
            val model = variant?.trim().orEmpty().ifBlank {
                profile?.model?.substringBefore(',')?.trim().orEmpty()
            }
            return when {
                name.isNotBlank() && model.isNotBlank() && !name.equals(model, ignoreCase = true) ->
                    "$name · $model"
                model.isNotBlank() -> model
                name.isNotBlank() -> name
                else -> ""
            }
        }
    }
}
