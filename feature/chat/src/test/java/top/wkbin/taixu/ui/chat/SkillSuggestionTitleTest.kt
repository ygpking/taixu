package top.wkbin.taixu.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import top.wkbin.taixu.harness.SkillSuggestion

/**
 * 技能建议卡片标题：update 提案必须显示**真实目标技能名**。
 *
 * 背景：LLM 自由填写的 skillName 曾被原样当标题，用户看到
 * 「修复既有技能：Git 敏捷工作流」就以为改的是熟悉的那一个，而落库名字以提案为准
 * （第一波已改成"进化不改名"，但卡片从没告诉用户到底改的是谁）。
 */
class SkillSuggestionTitleTest {

    private fun suggestion(action: String, name: String, targetId: String? = null) = SkillSuggestion(
        id = "sg1",
        createdAt = 1L,
        action = action,
        skillName = name,
        description = "d",
        systemPrompt = "p",
        targetSkillId = targetId,
    )

    @Test
    fun `update shows the resolved target name`() {
        val title = skillSuggestionTitle(
            suggestion("update", "完全不同的新名字", "custom_1"),
            targetSkillName = "周报整理",
        )
        assertEquals("将更新：周报整理", title)
    }

    @Test
    fun `update falls back to the proposal name when the target is gone`() {
        val title = skillSuggestionTitle(
            suggestion("update", "技能已删", "gone"),
            targetSkillName = null,
        )
        assertEquals("目标技能被删时不得空白", "技能已删", title)
    }

    @Test
    fun `create keeps the proposal name`() {
        val title = skillSuggestionTitle(
            suggestion("create", "新技能", null),
            targetSkillName = "不该被用上",
        )
        assertEquals("create 提案不受目标名影响", "新技能", title)
    }

    @Test
    fun `blank target name falls back`() {
        val title = skillSuggestionTitle(suggestion("update", "提案名", "custom_1"), targetSkillName = "   ")
        assertEquals("提案名", title)
    }
}
