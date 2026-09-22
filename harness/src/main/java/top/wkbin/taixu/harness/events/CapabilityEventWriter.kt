package top.wkbin.taixu.harness.events

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.projection.LiveMessagePort

import top.wkbin.taixu.harness.mcp.McpManager
import top.wkbin.taixu.harness.skill.SkillDecisionResolver
import top.wkbin.taixu.harness.skill.TurnSkillDecision
import top.wkbin.taixu.harness.skill.SkillInjectionMemory

/**
 * @提及 能力事件写入器：当用户消息提及技能或 MCP 服务时，
 * 在会话内插入一条幂等的 [CapabilityEvent] 展示卡片（同一用户消息下不重复）。
 */
@Singleton
class CapabilityEventWriter @Inject constructor(
    private val port: LiveMessagePort,
    private val skillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val mcpManager: McpManager? = null,
) {
    suspend fun writeIfMentioned(
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
        model: ModelConfig,
        latestUserMessage: String = "",
    ) {
        if (userMessageId.isBlank()) return
        // 早退保护（避免能耗回归）：本方法每轮对话都会被调用。若无 @提及 且无需自动匹配，
        // 必须在此处短路，否则每轮都会白读一次 Room（技能表 + MCP 表）——
        // 原本 `if (mentionedNames.isEmpty()) return` 提供的零成本特性不能被削弱。
        val hasMentions = mentionedNames.isNotEmpty()
        // 口径必须与甲（SystemPromptBuilder 的自动注入）严格一致：那边用「工具调用模式」判定，
        // 而 ApiContextAssembler 会把 pureChatMode 映射成 ToolCallMode.DISABLED。
        // 因此这里也要覆盖「非纯聊天但用户手动选了禁用工具」的情形，否则会出现
        // 「卡片写了系统自动匹配、提示里其实什么都没注入」的两侧漂移。
        val toolDisabled = model.pureChatMode || model.toolCallMode == ToolCallMode.DISABLED
        val wantsAutoMatch = !toolDisabled && latestUserMessage.isNotBlank()
        if (!hasMentions && !wantsAutoMatch) return

        val existing = port.snapshot(sessionId)
        // 与系统提示注入侧**同一个 resolver、同一份输入**：此前两侧各算一遍且归一口径不同
        // （这边裸 lowercase、那边 NFKC；这边不过滤 isEnabled、那边过滤），于是一个 @提及
        // 会同时得到"正文已注入"和"未匹配请确认拼写"两段互相矛盾的系统提示，
        // 禁用技能更会被写成"已生效"卡片。
        val skills = runCatching { skillRepository.allSkills.first() }.getOrDefault(emptyList())
        val decision = SkillDecisionResolver.resolveFromMentions(
            rawMentions = mentionedNames,
            latestUserMessage = latestUserMessage,
            allSkills = skills,
            toolCallMode = if (toolDisabled) ToolCallMode.DISABLED else model.toolCallMode,
            stickyIds = SkillInjectionMemory.stickyIds(sessionId),
        )
        // MCP 事件按 mentionedNames 走（与技能解析无关：@一个 MCP 服务/工具时技能侧可能为空），
        // 技能事件按 decision.mentionedIds 走（已过 isEnabled / 空正文门禁）。
        if (mentionedNames.isNotEmpty()) {
            if (decision.mentionedIds.isNotEmpty()) {
                writeSkillEvents(existing, sessionId, userMessageId, decision)
            }
            writeMcpEvents(existing, sessionId, userMessageId, decision.rawMentions, model)
        }
        // 自动匹配是「无声遗漏」的另一面：系统代替模型做了技能加载决策，
        // 若不在会话里留下可见痕迹，用户与开发者都无从判断"到底用了没用"。
        // pureChatMode（工具禁用）下系统提示不注入技能正文，此处同步跳过，保持两侧一致。
        if (wantsAutoMatch) {
            writeAutoMatchedSkillEvents(existing, sessionId, userMessageId, decision)
        }
    }

    private suspend fun writeAutoMatchedSkillEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        decision: TurnSkillDecision,
    ) {
        decision.autoMatchedIds.forEach { skillId ->
            val skill = decisionSkillName(decision, skillId) ?: return@forEach
            appendEventOnce(
                existing,
                sessionId,
                id = "auto:$userMessageId:$skillId",
                kind = CapabilityEvent.Kind.SKILL,
                name = skill,
                // 不能说"已直接注入"：注入侧还有一层累计字符预算（24K），超限的技能会被跳过。
                // 卡片无法在那个时刻知道结果，措辞必须对两种情况都成立。
                description = "系统自动匹配命中，指导规则按上下文预算注入",
            )
        }
    }

    private suspend fun writeSkillEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        decision: TurnSkillDecision,
    ) {
        // mentionedIds 已经过 resolver 的 isEnabled / 空正文门禁——此前这里用 allSkills
        // 且不过滤，@一个被禁用的技能也会写"已生效"卡片，而同一请求里正文并未注入、
        // 系统提示还把它列进"未匹配"，三处信号互相矛盾。
        decision.mentionedIds.forEach { skillId ->
            val skill = decisionSkillName(decision, skillId) ?: return@forEach
            appendEventOnce(
                existing,
                sessionId,
                id = "skill:$userMessageId:$skillId",
                kind = CapabilityEvent.Kind.SKILL,
                name = skill,
                description = "已按 @提及 注入该技能的指导规则",
            )
        }
    }

    private suspend fun writeMcpEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
        model: ModelConfig,
    ) {
        val configuredMcp = runCatching { mcpServerRepository.servers.first() }
            .getOrDefault(emptyList())
            .map { it.id to it.name }
        val discoveredMcp = model.dynamicMcpTools.map { it.serverId to it.serverName }
        (configuredMcp + discoveredMcp)
            .distinctBy { it.first }
            .filter { (serverId, serverName) -> serverId.lowercase() in mentionedNames || serverName.lowercase() in mentionedNames }
            .forEach { (serverId, serverName) ->
                val lastError = mcpManager?.getLastError(serverId)
                val isMounted = model.dynamicMcpTools.any { it.serverId == serverId }
                val toolCount = model.dynamicMcpTools.count { it.serverId == serverId }
                val desc = when {
                    isMounted -> "MCP 工具已挂载（$toolCount 个工具，模型可按需调用）"
                    lastError != null -> "⚠️ MCP 服务异常：$lastError"
                    else -> "已选择 MCP 服务，正在尝试发现工具..."
                }
                appendEventOnce(
                    existing,
                    sessionId,
                    id = "mcp:$userMessageId:$serverId",
                    kind = CapabilityEvent.Kind.MCP,
                    name = serverName,
                    description = desc,
                )
            }
    }

    /** 从仓储拿技能名（卡片只需要名字；拿不到就跳过该卡片，不写无名事件）。 */
    private suspend fun decisionSkillName(decision: TurnSkillDecision, skillId: String): String? {
        if (skillId.isBlank()) return null
        return runCatching { skillRepository.allSkills.first() }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == skillId }
            ?.name
    }

    private suspend fun appendEventOnce(
        existing: List<HarnessMessage>,
        sessionId: String,
        id: String,
        kind: CapabilityEvent.Kind,
        name: String,
        description: String,
    ) {
        if (existing.none { message -> message is CapabilityEvent && message.id == id }) {
            port.append(sessionId, CapabilityEvent(id, System.currentTimeMillis(), kind, name, description))
        }
    }
}
