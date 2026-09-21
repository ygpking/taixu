package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.skill.SkillEvolutionAdvisor

/**
 * 技能进化顾问（借鉴千问「对话后技能沉淀/进化」）的纯逻辑测试：
 * 提案解析、内容护栏与归一化（update 目标校验/降级、create 去重）。
 */
class SkillEvolutionAdvisorTest {

    @Test
    fun `parses plain and fenced json responses`() {
        val plain = """{"action":"create","name":"周报整理","description":"整理零散记录为周报","trigger":"weekly","system_prompt":"你是周报助手","reason":"工作流可复用"}"""
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(plain)
        assertNotNull(proposal)
        assertEquals("create", proposal!!.action)
        assertEquals("周报整理", proposal.name)

        val fenced = "```json\n{\"action\":\"none\"}\n```"
        assertEquals("none", SkillEvolutionAdvisor.parseAdvisorResponse(fenced)?.action)
    }

    @Test
    fun `malformed responses yield null`() {
        assertNull(SkillEvolutionAdvisor.parseAdvisorResponse("没有 JSON"))
        assertNull(SkillEvolutionAdvisor.parseAdvisorResponse("{broken"))
    }

    @Test
    fun `content is bounded by guard rails`() {
        val hugeName = "n".repeat(100)
        val hugePrompt = "p".repeat(20000)
        val raw = "{\"action\":\"create\",\"name\":\"$hugeName\",\"system_prompt\":\"$hugePrompt\"}"
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(raw)!!
        assertEquals(20, proposal.name.length)
        assertEquals(8000, proposal.systemPrompt.length)
    }

    @Test
    fun `create proposal dedupes against existing skill names`() {
        val skills = listOf(AgentSkill(id = "s1", name = "周报整理", description = "", systemPrompt = "x"))
        val duplicate = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"create\",\"name\":\"周报整理\",\"system_prompt\":\"y\"}",
        )!!
        assertNull(SkillEvolutionAdvisor.normalizeProposal(duplicate, skills))
    }

    @Test
    fun `update falls back when target missing and downgrades builtin targets`() {
        val custom = AgentSkill(id = "custom_1", name = "旧技能", description = "", systemPrompt = "x", isBuiltin = false)
        val builtin = AgentSkill(id = "builtin_1", name = "内置技能", description = "", systemPrompt = "x", isBuiltin = true)

        val missingTarget = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"旧技能\",\"system_prompt\":\"y\",\"target_skill_id\":\"gone\"}",
        )!!
        assertEquals("custom_1", SkillEvolutionAdvisor.normalizeProposal(missingTarget, listOf(custom))!!.target_skill_id)

        val builtinTarget = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"内置技能\",\"system_prompt\":\"y\",\"target_skill_id\":\"builtin_1\"}",
        )!!
        val normalized = SkillEvolutionAdvisor.normalizeProposal(builtinTarget, listOf(builtin))!!
        assertEquals("create", normalized.action)
        assertNull(normalized.target_skill_id)
        assertTrue(normalized.name.contains("进化"))
    }

    @Test
    fun `digest starts from last user message and bounds tool output`() {
        val hugeOutput = "o".repeat(5000)
        val messages = listOf(
            UserMessage(id = "u0", createdAt = 0, text = "更早的请求"),
            AssistantText(id = "a0", createdAt = 1, text = "更早的回复"),
            UserMessage(id = "u1", createdAt = 2, text = "本次请求"),
            ToolCall(id = "t1", createdAt = 3, tool = HarnessTool.READ, args = kotlinx.serialization.json.JsonObject(emptyMap())),
            ToolResult(id = "r1", createdAt = 4, toolCallId = "t1", success = true, output = hugeOutput),
        )
        val digest = SkillEvolutionAdvisor.buildConversationDigest(messages)!!
        assertTrue(digest.contains("本次请求"))
        assertTrue(!digest.contains("更早的请求"))
        assertTrue(digest.length < 9000)
        assertNull(SkillEvolutionAdvisor.buildConversationDigest(listOf(AssistantText(id = "a", createdAt = 0, text = "hi"))))
    }

    // ---------- 归一化护栏（第一波回归） ----------

    /**
     * 回归（P2）：update 落库曾以 LLM 自由填写的提案名覆盖目标技能名——用户熟悉的技能被
     * 静默改名，历史会话里按名字 @提及/触发的引用全部失配，而卡片没显示过真实目标。
     */
    @Test
    fun `update proposal keeps the target skill name`() {
        val target = AgentSkill(id = "custom_1", name = "周报整理", description = "", systemPrompt = "x", isBuiltin = false)
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"完全不同的新名字\",\"system_prompt\":\"y\",\"target_skill_id\":\"custom_1\"}",
        )!!
        val normalized = SkillEvolutionAdvisor.normalizeProposal(proposal, listOf(target))!!
        assertEquals("update", normalized.action)
        assertEquals("进化只改内容不改名", "周报整理", normalized.name)
    }

    @Test
    fun `update degraded to create still dedupes against existing names`() {
        val builtin = AgentSkill(id = "builtin_1", name = "内置技能", description = "", systemPrompt = "x", isBuiltin = true)
        // 目标失效、按名也找不到非内置同名 → 降级 create；若降级后又与既有内置技能同名，必须被去重挡掉。
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"内置技能\",\"system_prompt\":\"y\",\"target_skill_id\":\"gone\"}",
        )!!
        assertNull(
            "降级 create 必须重新过重名去重，否则会建议一个与既有内置技能同名的『新技能』",
            SkillEvolutionAdvisor.normalizeProposal(proposal, listOf(builtin)),
        )
    }

    @Test
    fun `builtin downgrade name stays within the 20 char cap`() {
        val builtin = AgentSkill(id = "builtin_1", name = "内置技能甲乙丙丁戊己庚辛壬癸", description = "", systemPrompt = "x", isBuiltin = true)
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"内置技能甲乙丙丁戊己庚辛壬癸\",\"system_prompt\":\"y\",\"target_skill_id\":\"builtin_1\"}",
        )!!
        val normalized = SkillEvolutionAdvisor.normalizeProposal(proposal, listOf(builtin))!!
        assertTrue("改名后不得突破 20 字上限：${normalized.name.length}", normalized.name.length <= 20)
        assertTrue(normalized.name.endsWith("-进化"))
    }

    /**
     * 回归（P2）：trigger 只补 "/" 前缀不做校验——含空格/中文/标点的触发命令会原样落库，
     * 用户在输入框永远敲不出这条命令（命令以空格分词），同时污染 SkillMatcher 的显著词表。
     */
    @Test
    fun `trigger is sanitized into a typeable slash command`() {
        assertEquals("weekly", SkillEvolutionAdvisor.sanitizeTrigger("Weekly"))
        assertEquals("weekly-report", SkillEvolutionAdvisor.sanitizeTrigger("Weekly Report"))
        assertEquals("weekly-report", SkillEvolutionAdvisor.sanitizeTrigger("/Weekly Report"))
        assertEquals("weekly", SkillEvolutionAdvisor.sanitizeTrigger("周报 Weekly"))
        // 纯非 ASCII（或纯标点）清洗后为空：该技能不提供斜杠触发，置 null
        assertNull(SkillEvolutionAdvisor.sanitizeTrigger("写周报 一键!"))
        assertNull(SkillEvolutionAdvisor.sanitizeTrigger("？？"))
        assertNull(SkillEvolutionAdvisor.sanitizeTrigger(null))
        assertNull(SkillEvolutionAdvisor.sanitizeTrigger("   "))
        assertEquals(20, SkillEvolutionAdvisor.sanitizeTrigger("a".repeat(50))!!.length)
    }
}
