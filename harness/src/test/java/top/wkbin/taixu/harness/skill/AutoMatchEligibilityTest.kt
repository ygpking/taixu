package top.wkbin.taixu.harness.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.ToolCallMode

/**
 * `autoMatchEligible` 门禁：会写持久状态的技能只准显式激活。
 *
 * 背景：自动匹配误报的代价对这类技能特别高——它们的正文指令模型调用
 * plan/memory/scratchpad，一次误注入就可能覆盖用户真实计划、留下跨会话记忆。
 * 第一波已给 `plan replace_active` 加了确认门；本波从**源头**把它们移出自动匹配池。
 */
class AutoMatchEligibilityTest {

    private fun skill(
        id: String,
        name: String,
        desc: String = "$name 描述",
        trigger: String? = null,
        autoMatch: Boolean = true,
    ) = AgentSkill(
        id = id,
        name = name,
        description = desc,
        systemPrompt = "技能正文",
        triggerCommand = trigger,
        autoMatchEligible = autoMatch,
    )

    private val persistent = skill(
        "agent_context",
        "上下文与任务记忆规划",
        "太墟核心系统能力：提供长期事实记忆 memory、任务执行规划 plan 与工作草稿便签 scratchpad",
        "/context",
        autoMatch = false,
    )
    private val git = skill(
        "git_workflow",
        "Git 敏捷工作流",
        "自动化 Git 状态分析、分支管理、冲突诊断",
        "/git",
    )

    @Test
    fun `auto match never picks a skill that opted out`() {
        // 任务文本明显落在上下文/记忆领域。前置条件用"打开开关的副本"验证：
        // 同一个技能、同一段任务，唯一差别就是 autoMatchEligible——这样才证明拦住它的是开关，
        // 而不是这段任务本来就命中不了。
        val task = "帮我规划一下这个任务的步骤，并把关键结论记到长期记忆里"
        assertTrue(
            "前置条件：打开开关后该任务本应命中",
            SkillMatcher.match(task, listOf(persistent.copy(autoMatchEligible = true))).isNotEmpty(),
        )

        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = task,
            allSkills = listOf(persistent),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertTrue("已退出的技能不得进自动匹配", decision.autoMatchedIds.isEmpty())
        assertTrue(decision.injectedIds.isEmpty())
    }

    @Test
    fun `explicit mention still activates an opted-out skill`() {
        val decision = SkillDecisionResolver.resolveFromMentions(
            rawMentions = setOf("上下文与任务记忆规划"),
            latestUserMessage = "",
            allSkills = listOf(persistent),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertEquals("显式 @提及 仍然可以激活", listOf("agent_context"), decision.injectedMentionIds)
    }

    @Test
    fun `eligibility is per skill not global`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
            allSkills = listOf(persistent, git),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertEquals("普通技能照常自动匹配", listOf("git_workflow"), decision.autoMatchedIds)
    }

    @Test
    fun `sticky pool also respects the flag`() {
        // 上一轮显式激活过 persistent；粘性不能把"显式激活"偷偷转成"每轮自动注入"
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "继续，把上一步的结论补全",
            allSkills = listOf(persistent),
            toolCallMode = ToolCallMode.NATIVE,
            stickyIds = setOf("agent_context"),
        )
        assertTrue("粘性池同样要求 autoMatchEligible", decision.autoMatchedIds.isEmpty())
    }

    @Test
    fun `builtin context skill is opted out of auto matching`() {
        val preset = top.wkbin.taixu.core.model.BuiltinSkills.presets.first { it.id == "agent_context" }
        assertFalse("内置上下文技能默认退出自动匹配", preset.autoMatchEligible)

        val others = top.wkbin.taixu.core.model.BuiltinSkills.presets.filter { it.id != "agent_context" }
        assertTrue("其余内置技能默认仍参与自动匹配", others.all { it.autoMatchEligible })
    }

    @Test
    fun `pure chat mode injects nothing regardless`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.DISABLED,
        )
        assertTrue(decision.injectedIds.isEmpty())
    }
}
