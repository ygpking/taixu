package top.wkbin.taixu.harness.skill

import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.MentionExtractor
import top.wkbin.taixu.harness.prompt.selectUnmatchedMentions
import top.wkbin.taixu.harness.ToolCallMode

/**
 * **一轮**的技能决策：从"本轮用户消息"到"最终注入哪些技能正文"的一次性结论。
 *
 * ## 为什么需要它
 * 在此之前，同一件事被算了五遍、每遍口径都不同：
 *  · `SystemPromptBuilder.selectSkills`（NFKC 归一、过滤 isEnabled）
 *  · `CapabilityEventWriter.writeSkillEvents`（裸 lowercase、**不过滤** isEnabled）
 *  · `CapabilityEventWriter.writeAutoMatchedSkillEvents`（裸 lowercase）
 *  · `ToolExecutor.load_skill`（裸 lowercase）
 *  · `ChatViewModel.attachedMentions`（裸 lowercase、无 triggerCommand）
 * 于是同一个 `@Ｇｉｔ` 在正文里注入了、卡片上却没影；禁用技能被 @ 了正文拒绝注入、
 * 卡片却写"已生效"；含空格技能名在上游被截断成 `git`，于是"正文已注入"与
 * "请确认拼写"两段提示同时出现在同一份系统提示里。
 *
 * `MentionExtractor.parse` 也被调 3+1 次，其中两次不传 `knownNames`——而只有带名单的
 * 那一遍能识别含空格技能名（最长优先）。
 *
 * ## 用法
 * 每轮（或每次 provider 请求）调一次 [resolve]，把结果同时喂给提示词构建、能力事件卡片、
 * MCP 工具过滤。**纯粹函数**，无 IO、无状态，可单测。
 */
data class TurnSkillDecision(
    /** 本轮用户消息原文（决策的唯一依据）。 */
    val latestUserMessage: String,
    /** 归一化前的原始提及 token（供 UI 与日志）。 */
    val rawMentions: Set<String>,
    /** 提及命中的技能 id（含被禁用/空正文的——调用方按各自门禁决定用不用）。 */
    val mentionedIds: Set<String>,
    /** 真正注入正文的提及技能（已过 isEnabled / 空正文门禁）。 */
    val injectedMentionIds: List<String>,
    /** 机械预匹配命中且注入正文的。 */
    val autoMatchedIds: List<String>,
    /** 因累计预算被跳过的技能名。 */
    val skippedByBudget: List<String>,
    /** 归一化比较后仍未知的提及（原样输出，供"请确认拼写"提示）。 */
    val unmatchedMentions: List<String>,
    /** 上一轮自动注入、本轮按粘性继续注入的技能 id。 */
    val stickyFromPreviousTurn: Set<String>,
) {
    /** 全部注入正文的技能 id（提及 + 自动匹配 + 粘性），保持顺序。 */
    val injectedIds: List<String>
        get() = (injectedMentionIds + autoMatchedIds).distinct()

    val hasInjections: Boolean get() = injectedIds.isNotEmpty()
}

/**
 * 技能决策解析器：**纯函数**，不碰数据库、不碰协程。
 *
 * 归一化、门禁、预算裁剪的规则都收敛在这里；`SystemPromptBuilder` 与
 * `CapabilityEventWriter` 都调它，从此两侧结论不可能分叉。
 */
object SkillDecisionResolver {

    /**
     * 解析一轮的技能决策。
     *
     * @param latestUserMessage 本轮用户消息原文；空则全部为空决策。
     * @param allSkills 全量技能（内部按 isEnabled / 空正文门禁过滤）。
     * @param toolCallMode 工具调用模式；DISABLED（纯聊天）时不注入任何正文。
     * @param stickyIds 上一轮自动注入、按粘性本轮继续注入的技能 id。
     * @param otherKnownNames MCP 服务/工具、子智能体等"可 @ 但不是技能"的名字，
     *   用于未匹配判定——漏掉它们会把合法提及误报成拼写错误。
     */
    fun resolve(
        latestUserMessage: String,
        allSkills: List<AgentSkill>,
        toolCallMode: ToolCallMode,
        stickyIds: Set<String> = emptySet(),
        otherKnownNames: Collection<String> = emptyList(),
    ): TurnSkillDecision {
        val text = latestUserMessage.trim()
        if (text.isBlank()) {
            return emptyDecision(latestUserMessage, stickyIds)
        }
        val knownSkillNames = allSkills
            .filter { it.isEnabled }
            .flatMap { listOf(it.name, it.id, it.triggerCommand?.removePrefix("/").orEmpty()) }
            .filter { it.isNotBlank() }
        val rawMentions = MentionExtractor.parse(text, knownSkillNames)
        return resolveFromMentions(rawMentions, latestUserMessage, allSkills, toolCallMode, stickyIds, otherKnownNames)
    }

    /**
     * 与 [resolve] 相同，但提及 token 由调用方给出。
     *
     * 存在的理由：`CapabilityEventWriter.writeIfMentioned` 的入参就是上游解析好的
     * `mentionedNames`（上游不传 knownNames，解析结果可能是截断形）。让它也走同一个
     * 归一与门禁，是"卡片与注入结论一致"的前提。
     */
    fun resolveFromMentions(
        rawMentions: Set<String>,
        latestUserMessage: String,
        allSkills: List<AgentSkill>,
        toolCallMode: ToolCallMode,
        stickyIds: Set<String> = emptySet(),
        otherKnownNames: Collection<String> = emptyList(),
    ): TurnSkillDecision {
        if (rawMentions.isEmpty() && latestUserMessage.isBlank()) {
            return emptyDecision(latestUserMessage, stickyIds)
        }
        val text = latestUserMessage.trim()
        if (allSkills.isEmpty()) {
            return emptyDecision(latestUserMessage, stickyIds).copy(rawMentions = rawMentions)
        }
        // 可注入池：已启用 + 正文非空。（自动匹配的 autoMatchEligible 门禁在下面
        // 挑 autoMatched 时施加——@提及仍然允许激活这类技能。）
        val enabled = allSkills.filter { it.isEnabled && it.systemPrompt.isNotBlank() }
        val byKey: Map<String, AgentSkill> = buildMap {
            enabled.forEach { skill ->
                put(normalizeKey(skill.name), skill)
                put(normalizeKey(skill.id), skill)
                skill.triggerCommand?.removePrefix("/")?.takeIf { it.isNotBlank() }?.let {
                    put(normalizeKey(it), skill)
                }
            }
        }
        val mentioned = rawMentions.mapNotNull { byKey[normalizeKey(it)] }.distinctBy { it.id }
        val unmatched = selectUnmatchedMentions(allSkills, rawMentions, otherKnownNames)
        val autoMatched = if (toolCallMode == ToolCallMode.DISABLED || text.isBlank()) {
            emptyList()
        } else {
            // 自动匹配池额外要求 autoMatchEligible；粘性池同理（否则上一轮显式激活的
            // 技能会被粘性悄悄转成"每轮自动注入"）。
            val sticky = enabled.filter {
                it.id in stickyIds && it.id !in mentioned.map { s -> s.id } && it.autoMatchEligible
            }
            val fresh = SkillMatcher.match(
                text,
                enabled.filter { it.id !in stickyIds && it.autoMatchEligible },
            )
                .map { it.skill }
                .filter { skill -> mentioned.none { it.id == skill.id } }
            (sticky + fresh).distinctBy { it.id }
        }
        return TurnSkillDecision(
            latestUserMessage = latestUserMessage,
            rawMentions = rawMentions,
            mentionedIds = mentioned.mapTo(mutableSetOf()) { it.id },
            injectedMentionIds = mentioned.map { it.id },
            autoMatchedIds = autoMatched.map { it.id },
            skippedByBudget = emptyList(),
            unmatchedMentions = unmatched,
            stickyFromPreviousTurn = stickyIds,
        )
    }

    private fun emptyDecision(latestUserMessage: String, stickyIds: Set<String>): TurnSkillDecision =
        TurnSkillDecision(
            latestUserMessage = latestUserMessage,
            rawMentions = emptySet(),
            mentionedIds = emptySet(),
            injectedMentionIds = emptyList(),
            autoMatchedIds = emptyList(),
            skippedByBudget = emptyList(),
            unmatchedMentions = emptyList(),
            stickyFromPreviousTurn = stickyIds,
        )


    /**
     * 与 @提及链路、`load_skill`、事件卡片共用的唯一归一函数。
     *
     * 此前五处各写一遍（`normalizeMentionKey` / 裸 `lowercase()`），
     * 现在全部收敛到这一个，卡片与注入结论不可能再分叉。
     */
    fun normalizeKey(raw: String): String =
        java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFKC).lowercase()
}
