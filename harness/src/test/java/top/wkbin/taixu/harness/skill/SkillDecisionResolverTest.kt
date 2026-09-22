package top.wkbin.taixu.harness.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.prompt.selectUnmatchedMentions

/**
 * 本轮技能决策对象（`TurnSkillDecision` / `SkillDecisionResolver`）的契约测试。
 *
 * 这批用例守的是**四路一致性**：此前「mention → 技能」解析实现了五份、归一口径互不相同，
 * 于是同一个 @提及会在"正文已注入""未匹配请确认拼写""技能已加载""UI 附件"四处得到
 * 互相矛盾的结论。现在它们共用同一个 resolver，这些用例把一致性钉住。
 */
class SkillDecisionResolverTest {

    private fun skill(
        id: String,
        name: String,
        desc: String = "$name 描述",
        trigger: String? = null,
        enabled: Boolean = true,
        body: String = "技能正文",
    ) = AgentSkill(
        id = id,
        name = name,
        description = desc,
        systemPrompt = body,
        triggerCommand = trigger,
        isEnabled = enabled,
        isBuiltin = false,
    )

    private val git = skill("git_workflow", "Git 敏捷工作流", "自动化 Git 状态分析、分支管理、冲突诊断", "/git")
    private val pdf = skill("pdf_export", "PDF 导出", "把文档导出为 PDF 文件")
    private val disabled = skill("old", "旧技能", "旧技能的描述", enabled = false)
    private val blank = skill("bb", "空技能", "空技能描述", body = "   ")

    @Test
    fun `mention resolves through one normalization for all four surfaces`() {
        val decision = SkillDecisionResolver.resolveFromMentions(
            rawMentions = setOf("Ｇｉｔ"),
            latestUserMessage = "",
            allSkills = listOf(git, pdf),
            toolCallMode = ToolCallMode.NATIVE,
        )
        // 全角输入：@提及链路与 load_skill/事件卡片共用同一归一，结论必须一致
        assertEquals(listOf("git_workflow"), decision.injectedMentionIds)
    }

    @Test
    fun `disabled and blank body skills never reach the decision`() {
        val decision = SkillDecisionResolver.resolveFromMentions(
            rawMentions = setOf("旧技能", "空技能", "Git 敏捷工作流"),
            latestUserMessage = "",
            allSkills = listOf(git, disabled, blank),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertEquals("禁用与空正文技能不得进决策", listOf("git_workflow"), decision.injectedMentionIds)
        assertTrue("禁用技能应进入未匹配提示", "旧技能" in decision.unmatchedMentions)
    }

    @Test
    fun `mention and auto match of the same skill are not double counted`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "@Git 敏捷工作流 帮我处理 git 分支合并冲突诊断",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertEquals("提及命中一次", listOf("git_workflow"), decision.injectedMentionIds)
        assertTrue("不得再把同一技能记为自动匹配", decision.autoMatchedIds.isEmpty())
    }

    @Test
    fun `pure chat mode injects nothing`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "帮我处理 git 分支合并冲突",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.DISABLED,
        )
        assertTrue(decision.autoMatchedIds.isEmpty())
        assertTrue(decision.injectedIds.isEmpty())
    }

    /**
     * 回归（P2）：粘性。追问轮的关键词一旦不复现，已注入的指导规则不应静默丢失——
     * 那等于这套机制要消灭的"无声遗漏"每隔一轮复发一次。
     */
    @Test
    fun `sticky skills survive a follow up turn whose keywords do not recur`() {
        // 首轮用一个真正域内的任务：按家族阈值（中文 3 个 bigram 或英文 2 个词）才定罪，
        // "git + 流程"这种单英文词+单中文词本就勉强——那是第二波为压误报主动付出的召回代价。
        val first = SkillDecisionResolver.resolve(
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertTrue("首轮应命中 git_workflow", first.autoMatchedIds.contains("git_workflow"))

        SkillInjectionMemory.reset()
        SkillInjectionMemory.record("s-1", first.autoMatchedIds)

        val followUp = SkillDecisionResolver.resolve(
            latestUserMessage = "继续，把冲突解决掉然后推送",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.NATIVE,
            stickyIds = SkillInjectionMemory.stickyIds("s-1"),
        )
        assertTrue(
            "追问轮关键词不复现时，粘性应续上已注入技能",
            followUp.autoMatchedIds.contains("git_workflow"),
        )

        SkillInjectionMemory.forget("s-1")
        assertTrue("forget 后不得残留", SkillInjectionMemory.stickyIds("s-1").isEmpty())
    }

    @Test
    fun `unmatched mentions exclude skills mcp tools and subagents`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "@未知技能 @cat 帮我看看",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.NATIVE,
            otherKnownNames = listOf("file-server", "cat"),
        )
        assertEquals("只有真正未知的提及应被判为未匹配", listOf("未知技能"), decision.unmatchedMentions)
    }

    @Test
    fun `blank user message yields an empty decision`() {
        val decision = SkillDecisionResolver.resolve(
            latestUserMessage = "   ",
            allSkills = listOf(git),
            toolCallMode = ToolCallMode.NATIVE,
        )
        assertFalse(decision.hasInjections)
        assertTrue(decision.unmatchedMentions.isEmpty())
    }

    /** 未匹配判定仍走同一个入口（事件卡片与提示共用）。 */
    @Test
    fun `unmatched selection stays the single entry point`() {
        val missed = selectUnmatchedMentions(listOf(git), setOf("未知"), listOf("cat"))
        assertEquals(listOf("未知"), missed)
    }
}
