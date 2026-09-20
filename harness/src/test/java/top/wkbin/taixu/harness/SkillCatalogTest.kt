package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.prompt.renderSkillCatalog

/**
 * 借鉴 OpenMinis 技能模型的目录渲染契约：元数据常驻、正文按需（load_skill）。
 */
class SkillCatalogTest {

    private fun skill(id: String, name: String, desc: String = "用途描述", enabled: Boolean = true) =
        AgentSkill(
            id = id,
            name = name,
            description = desc,
            systemPrompt = "skill body",
            triggerCommand = null,
            iconName = "",
            isEnabled = enabled,
            isBuiltin = false,
            category = "自定义",
        )

    @Test
    fun `catalog lists enabled skills with guidance`() {
        val text = renderSkillCatalog(
            listOf(skill("s1", "pdf-export", "把文档导出为 PDF")),
            excludeIds = emptySet(),
        )
        assertTrue(text.contains("## 可用技能"))
        assertTrue(text.contains("load_skill"))
        assertTrue(text.contains("pdf-export"))
        assertTrue(text.contains("把文档导出为 PDF"))
    }

    /**
     * 回归防线：目录文案必须给出「开工前主动扫描」的动作指引。
     *
     * 背景：旧文案只写「当用户请求与某条描述匹配时」加载，属被动等匹配。实际运行中模型
     * 接手任务后往往凭自身记忆直接开干，从不主动扫目录，成套的专业做法（含踩过的坑）被闲置。
     * 本断言锁定主动措辞，防止回退。
     */
    @Test
    fun `catalog tells model to scan before starting`() {
        val text = renderSkillCatalog(
            listOf(skill("s1", "pdf-export", "把文档导出为 PDF")),
            excludeIds = emptySet(),
        )
        assertTrue("目录应要求开工前先扫：$text", text.contains("开工前"))
        assertTrue("目录应给出命中后的加载动作：$text", text.contains("load_skill"))
    }

    @Test
    fun `excluded and disabled skills are omitted`() {
        val text = renderSkillCatalog(
            listOf(
                skill("s1", "active"),
                skill("s2", "disabled", enabled = false),
            ),
            excludeIds = setOf("s1"),
        )
        assertFalse(text.contains("active"))
        assertFalse(text.contains("disabled"))
        assertFalse(text.contains("## 可用技能"))
    }

    @Test
    fun `long descriptions are bounded`() {
        val text = renderSkillCatalog(
            listOf(skill("s1", "long", desc = "很长的描述".repeat(200))),
            excludeIds = emptySet(),
        )
        val line = text.lineSequence().first { it.startsWith("- ") }
        assertTrue(line.length < 200)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `catalog is capped at 24 entries`() {
        val skills = (1..30).map { skill("s$it", "skill-$it") }
        val text = renderSkillCatalog(skills, excludeIds = emptySet())
        assertEquals(24, text.lineSequence().count { it.startsWith("- ") })
    }
}
