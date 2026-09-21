package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.prompt.selectAutoMatchedSkills
import top.wkbin.taixu.harness.skill.SkillMatcher

/**
 * 机械预匹配器测试 —— 治本目标：**不再依赖模型自觉加载技能**。
 *
 * 缺陷背景：技能目录与 load_skill 工具早已就位，但"从看见到调用"全靠模型自觉，
 * 实测中模型常凭自身记忆直接开干，专业做法（含踩过的坑）被闲置，且遗漏**无声无息**。
 * [SkillMatcher] 把这一决策从模型自由裁量改为系统确定性判定。
 *
 * 本测试锁定三类语义：
 * ① 高精度命中（宁缺毋滥，避免噪声污染提示）；
 * ② 阈值不被高频虚词稀释（否则"随便说句话都能命中"）；
 * ③ 门禁与全链路口径一致（禁用技能 / 纯聊天模式 / 空任务文本一律不匹配）。
 */
class SkillMatcherTest {

    private fun skill(
        id: String,
        name: String,
        desc: String = "",
        category: String = "通用",
        trigger: String? = null,
        enabled: Boolean = true,
        body: String = "skill body",
    ) = AgentSkill(
        id = id,
        name = name,
        description = desc,
        systemPrompt = body,
        triggerCommand = trigger,
        iconName = "",
        isEnabled = enabled,
        isBuiltin = false,
        category = category,
    )

    private val gitSkill = skill(
        id = "git_workflow",
        name = "Git 敏捷工作流",
        desc = "自动化 Git 状态分析、分支管理、原子提交信息规范生成与冲突诊断",
        category = "版本控制",
        trigger = "/git",
    )

    private val pdfSkill = skill(
        id = "pdf_export",
        name = "PDF 导出",
        desc = "把文档导出为 PDF 文件",
        category = "文档处理",
    )

    // ---------- 正例：任务确实落在技能领域 ----------

    @Test
    fun `name substring match injects skill`() {
        val hits = SkillMatcher.match("帮我用 Git 敏捷工作流处理一下", listOf(gitSkill))
        assertEquals(1, hits.size)
        assertEquals("git_workflow", hits[0].skill.id)
    }

    @Test
    fun `english keyword match injects skill`() {
        val hits = SkillMatcher.match("帮我处理一下 git 分支合并冲突", listOf(gitSkill))
        assertTrue("git 相关任务应命中 Git 技能：$hits", hits.any { it.skill.id == "git_workflow" })
    }

    @Test
    fun `cjk keyword accumulation injects skill`() {
        // 描述含"文档导出"等中文显著词；任务里出现多个同域 2-gram 应累加过阈值
        val hits = SkillMatcher.match("把这个文档导出成 PDF 文件", listOf(pdfSkill))
        assertTrue("文档导出类任务应命中 PDF 技能：$hits", hits.any { it.skill.id == "pdf_export" })
    }

    // ---------- 反例：不应命中（精度优先） ----------

    @Test
    fun `unrelated task produces no hit`() {
        val hits = SkillMatcher.match("今天天气怎么样", listOf(gitSkill, pdfSkill))
        assertTrue("无关任务不应命中任何技能：$hits", hits.isEmpty())
    }

    @Test
    fun `single english keyword alone is below threshold`() {
        // 顺口提一句 git ≠ 要做 Git 工作流。单个英文词（3 分）必须不足以定罪。
        val hits = SkillMatcher.match("git", listOf(gitSkill))
        assertTrue("单个英文词不应越过阈值：$hits", hits.isEmpty())
    }

    @Test
    fun `generic particles do not dilute the threshold`() {
        // 高频泛用词（分析/使用/规则…）若参与匹配，会让无关任务被误判命中。
        val hits = SkillMatcher.match("请分析一下这个情况，看看处理方式是否支持", listOf(gitSkill))
        assertTrue("高频虚词不应造成误命中：$hits", hits.isEmpty())
    }

    // ---------- 门禁：与全链路口径一致 ----------

    @Test
    fun `disabled skills are never matched`() {
        val disabled = skill(
            id = "off",
            name = "Git 敏捷工作流",
            desc = "自动化 Git 状态分析、分支管理",
            category = "版本控制",
            trigger = "/git",
            enabled = false,
        )
        val hits = SkillMatcher.match("帮我处理 git 分支合并冲突诊断", listOf(disabled))
        assertTrue("禁用技能不得被自动匹配：$hits", hits.isEmpty())
    }

    @Test
    fun `blank task text produces no hit`() {
        assertTrue(SkillMatcher.match("", listOf(gitSkill)).isEmpty())
        assertTrue(SkillMatcher.match("   ", listOf(gitSkill)).isEmpty())
    }

    @Test
    fun `empty body skills are skipped`() {
        val emptyBody = skill(id = "e", name = "Git 敏捷工作流", desc = "Git 分支管理", body = "  ")
        assertTrue(SkillMatcher.match("帮我处理 git 分支合并", listOf(emptyBody)).isEmpty())
    }

    // ---------- 排序与上界 ----------

    @Test
    fun `hits are sorted by score descending and capped`() {
        val strong = skill(id = "strong", name = "PDF 导出", desc = "把文档导出为 PDF 文件", category = "文档处理")
        val weak = skill(id = "weak", name = "PDF 工具", desc = "PDF 相关工具", category = "文档处理")
        val hits = SkillMatcher.match("把这个文档导出成 PDF 文件", listOf(weak, strong), maxHits = 1)
        assertEquals(1, hits.size)
        assertEquals("strong", hits[0].skill.id)
    }

    // ---------- 注入决策（selectAutoMatchedSkills） ----------

    @Test
    fun `auto injection is skipped in pure chat mode`() {
        val result = selectAutoMatchedSkills(
            allSkills = listOf(gitSkill),
            latestUserMessage = "帮我处理 git 分支合并冲突",
            toolCallMode = ToolCallMode.DISABLED,
            excludedIds = emptySet(),
        )
        assertTrue("纯聊天模式下不得注入技能正文：$result", result.isEmpty())
    }

    @Test
    fun `auto injection excludes already mentioned skills`() {
        val result = selectAutoMatchedSkills(
            allSkills = listOf(gitSkill),
            latestUserMessage = "帮我处理 git 分支合并冲突",
            toolCallMode = ToolCallMode.NATIVE,
            excludedIds = setOf("git_workflow"),
        )
        assertTrue("已 @提及 的技能不得重复注入：$result", result.isEmpty())
    }

    @Test
    fun `auto injection works for native tool mode`() {
        val result = selectAutoMatchedSkills(
            allSkills = listOf(gitSkill, pdfSkill),
            latestUserMessage = "帮我处理 git 分支合并冲突诊断",
            toolCallMode = ToolCallMode.NATIVE,
            excludedIds = emptySet(),
        )
        assertFalse("原生工具模式下应产出注入候选：$result", result.isEmpty())
        assertEquals("git_workflow", result[0].id)
    }
}
