package top.wkbin.taixu.harness

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.skill.SkillEvolutionAdvisor

/**
 * 「技能进化顾问只应看到已启用技能」的口径一致性测试。
 *
 * 缺陷：顾问用 `skillRepository.allSkills`（含**被用户禁用**的技能）构造提示词并做归一化，
 * 而 `load_skill` 用 `activeSkills`、技能目录用 `isEnabled` 过滤 —— 三处口径不一。
 * 后果有两类无效建议：
 * ① 建议 `update` 一个已禁用技能：用户采纳也不生效（load_skill 拒载）；
 * ② 建议 `create` 时被"同名技能已存在（含禁用项）"挡下 → 静默 return null，白烧一次 LLM 调用。
 *
 * 修复：顾问改用 `activeSkills`。本测试固化"禁用技能不得参与归一化判定"这一语义。
 */
class SkillEvolutionAdvisorEnabledGateTest {

    private val enabled = AgentSkill(
        id = "custom_on", name = "周报整理", description = "", systemPrompt = "x", isBuiltin = false, isEnabled = true,
    )
    private val disabled = AgentSkill(
        id = "custom_off", name = "已禁用技能", description = "", systemPrompt = "x", isBuiltin = false, isEnabled = false,
    )

    private fun proposal(action: String, name: String, targetId: String? = null) =
        SkillEvolutionAdvisor.parseAdvisorResponse(
            buildString {
                append("{\"action\":\"").append(action).append("\",")
                append("\"name\":\"").append(name).append("\",")
                append("\"system_prompt\":\"y\"")
                if (targetId != null) append(",\"target_skill_id\":\"").append(targetId).append("\"")
                append("}")
            },
        )!!

    @Test
    fun `update targeting an enabled custom skill is kept as update`() {
        val result = SkillEvolutionAdvisor.normalizeProposal(proposal("update", "周报整理", "custom_on"), listOf(enabled))
        assertNotNull(result)
        org.junit.Assert.assertEquals("update", result!!.action)
        org.junit.Assert.assertEquals("custom_on", result.target_skill_id)
    }

    /**
     * 顾问只拿到 activeSkills 后，"指向已禁用技能 id"的提案会落进 `target == null` 分支：
     * 若名字也不在启用集里 → 降级为 create（而不是 update 一个用不了的技能）。
     */
    @Test
    fun `update targeting a skill outside the visible set degrades to create`() {
        // 模拟修复后的调用：visible 里只有启用技能，提案指向禁用技能的 id
        val result = SkillEvolutionAdvisor.normalizeProposal(
            proposal("update", "已禁用技能", "custom_off"),
            listOf(enabled),
        )
        assertNotNull("不应静默丢弃，而是降级为新建", result)
        org.junit.Assert.assertEquals("create", result!!.action)
        assertNull("降级后不应再带 update 目标", result.target_skill_id)
    }

    @Test
    fun `create whose name collides only with a disabled skill is not blocked`() {
        // visible 只有启用技能 → "已禁用技能"这个名字不构成冲突，create 应保留
        val result = SkillEvolutionAdvisor.normalizeProposal(proposal("create", "已禁用技能"), listOf(enabled))
        assertNotNull("禁用技能不应挡住同名新建", result)
        org.junit.Assert.assertEquals("create", result!!.action)

        // 对照组：名字与**可见（启用）**技能冲突时，按去重语义应丢弃
        assertNull(
            "同名启用技能已存在时应去重丢弃",
            SkillEvolutionAdvisor.normalizeProposal(proposal("create", "周报整理"), listOf(enabled)),
        )
    }

    @Test
    fun `builtin target still downgrades to a renamed create`() {
        val builtin = AgentSkill(
            id = "b1", name = "内置技能", description = "", systemPrompt = "x", isBuiltin = true, isEnabled = true,
        )
        val result = SkillEvolutionAdvisor.normalizeProposal(proposal("update", "内置技能", "b1"), listOf(builtin))
        assertNotNull(result)
        org.junit.Assert.assertEquals("create", result!!.action)
        org.junit.Assert.assertTrue("应改名以免与内置同名", result.name.endsWith("-进化") || result.name != "内置技能")
    }
}
