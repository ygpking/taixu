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
import top.wkbin.taixu.harness.skill.SkillMatcher

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
        if (hasMentions) {
            writeSkillEvents(existing, sessionId, userMessageId, mentionedNames)
            writeMcpEvents(existing, sessionId, userMessageId, mentionedNames, model)
        }
        // 自动匹配是「无声遗漏」的另一面：系统代替模型做了技能加载决策，
        // 若不在会话里留下可见痕迹，用户与开发者都无从判断"到底用了没用"。
        // 这里与系统提示的注入同源同算法（同一个 SkillMatcher），保证「事件卡片」与
        // 「实际注入内容」一致，不会出现卡片说有、提示里却没有的偏差。
        // pureChatMode（工具禁用）下系统提示不注入技能正文，此处同步跳过，保持两侧一致。
        if (wantsAutoMatch) {
            writeAutoMatchedSkillEvents(existing, sessionId, userMessageId, latestUserMessage, mentionedNames)
        }
    }

    private suspend fun writeAutoMatchedSkillEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        latestUserMessage: String,
        mentionedNames: Set<String>,
    ) {
        if (latestUserMessage.isBlank()) return
        val skills = runCatching { skillRepository.activeSkills.first() }.getOrDefault(emptyList())
        if (skills.isEmpty()) return
        // 已被 @提及 的技能走 writeSkillEvents 事件，不重复记为"自动匹配"。
        // 排除必须发生在 match 之前：match 默认只取 AUTO_INJECT_MAX 条，而全名 @提及的技能
        // NAME_SCORE 最高必然登顶；先 take 再排除会让混合轮唯一的自动注入名额被已提及技能吃掉
        // （与 SystemPromptBuilder.selectAutoMatchedSkills 同一口径，两处必须同步改）。
        val mentionedIds = skills.filter { skill ->
            val names = setOf(
                skill.name.lowercase(),
                skill.id.lowercase(),
                skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty(),
            )
            names.any { it.isNotBlank() && it in mentionedNames }
        }.mapTo(mutableSetOf()) { it.id }
        val candidates = if (mentionedIds.isEmpty()) {
            skills
        } else {
            skills.filter { it.id !in mentionedIds }
        }
        SkillMatcher.match(latestUserMessage, candidates)
            .forEach { hit ->
                appendEventOnce(
                    existing,
                    sessionId,
                    id = "auto:$userMessageId:${hit.skill.id}",
                    kind = CapabilityEvent.Kind.SKILL,
                    name = hit.skill.name,
                    description = "系统自动匹配命中，指导规则已直接注入",
                )
            }
    }

    private suspend fun writeSkillEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
    ) {
        val skills = runCatching { skillRepository.allSkills.first() }.getOrDefault(emptyList())
            .filter { skill ->
                val names = setOf(
                    skill.name.lowercase(),
                    skill.id.lowercase(),
                    skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty(),
                )
                names.any { it.isNotBlank() && it in mentionedNames }
            }
        skills.forEach { skill ->
            appendEventOnce(
                existing,
                sessionId,
                id = "skill:$userMessageId:${skill.id}",
                kind = CapabilityEvent.Kind.SKILL,
                name = skill.name,
                description = skill.description,
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
