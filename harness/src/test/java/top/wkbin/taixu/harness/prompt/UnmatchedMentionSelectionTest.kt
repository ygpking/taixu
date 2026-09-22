package top.wkbin.taixu.harness.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill

/**
 * 「未匹配的 @提及」判定契约。
 *
 * 背景（真实缺陷）：已知名单原先只含**已启用技能**，而 `effectiveMentions` 含消息里
 * 全部 @token。MCP 服务与子智能体同样是 @提及的一等公民（`HarnessProviderRunner`
 * 用同一份 mentionedNames 挂载 MCP 工具），于是 `@浏览器` 这类合法 MCP 提及
 * **每一轮**都被判成"未匹配到任何已启用技能"注入系统提示——既噪声，
 * 还会诱导模型反过来要求用户"修正拼写"。
 */
class UnmatchedMentionSelectionTest {

    private fun skill(
        name: String,
        enabled: Boolean = true,
        trigger: String? = null,
    ) = AgentSkill(
        id = "custom_$name",
        name = name,
        description = "desc",
        systemPrompt = "body",
        triggerCommand = trigger,
        iconName = "star",
        isEnabled = enabled,
        isBuiltin = false,
        isImmutable = false,
        category = "自定义",
        resourcePath = null,
    )

    private val skills = listOf(
        skill("Git 敏捷工作流", trigger = "/git-workflow"),
        skill("旧技能", enabled = false),
    )

    @Test
    fun `禁用技能不算已知名（@ 了也提示未匹配）`() {
        val missed = selectUnmatchedMentions(skills, setOf("旧技能"))
        assertEquals(listOf("旧技能"), missed)
    }

    @Test
    fun `命中技能名 id 或 trigger 都算已知`() {
        val missed = selectUnmatchedMentions(
            skills,
            setOf("git 敏捷工作流", "custom_git 敏捷工作流", "git-workflow"),
        )
        assertTrue("全部命中已知名时不得提示：$missed", missed.isEmpty())
    }

    @Test
    fun `MCP 服务与子智能体名不再被误报为未匹配技能`() {
        val missed = selectUnmatchedMentions(
            allSkills = skills,
            mentionedNames = setOf("浏览器", "sub-1"),
            otherKnownNames = listOf("浏览器", "srv_1", "sub-1", "代码审查员"),
        )
        assertTrue("合法 MCP / 子智能体提及不得进未匹配列表：$missed", missed.isEmpty())
    }

    @Test
    fun `既非技能也非 MCP 与子智能体的提及仍会被提示`() {
        val missed = selectUnmatchedMentions(
            allSkills = skills,
            mentionedNames = setOf("Gitt", "浏览器"),
            otherKnownNames = listOf("浏览器"),
        )
        assertEquals("保留原大小写输出（仅比较时归一化）", listOf("Gitt"), missed)
    }

    @Test
    fun `归一化后比较（大小写与全角）`() {
        val missed = selectUnmatchedMentions(
            allSkills = skills,
            mentionedNames = setOf("ＧＩＴ 敏捷工作流"),
        )
        assertTrue("全角输入折半角后应命中：$missed", missed.isEmpty())
    }

    /**
     * 回归（P3）：MCP **工具名**级 @提及 是挂载路径合法支持的（HarnessProviderRunner 用
     * mentionedNames 筛 dynamicMcpTools），但 otherKnownNames 原先只收服务 id/名——
     * `@cat` 这类工具提及会被判成"未匹配到任何已启用技能，请确认拼写"，既噪声
     * 还会诱导模型要求用户改一个本来正确的写法。
     */
    @Test
    fun `mcp tool names count as known so they are not reported as typos`() {
        val missed = selectUnmatchedMentions(
            allSkills = skills,
            mentionedNames = setOf("cat", "grep", "未知技能"),
            otherKnownNames = listOf("file-server", "cat", "grep"),
        )
        assertEquals("只有真正未知的提及应被提示", listOf("未知技能"), missed)
    }
}
