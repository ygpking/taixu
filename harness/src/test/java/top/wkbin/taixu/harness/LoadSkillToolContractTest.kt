package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** load_skill 工具契约（借鉴 OpenMinis 的按需技能加载）。 */
class LoadSkillToolContractTest {

    @Test
    fun `load_skill exposes name lookup parameter`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "load_skill" }
        val encoded = tool.function.parameters.toString()
        assertTrue(encoded.contains("\"name\""))
    }

    /**
     * 回归防线：工具描述必须点名「开工前先扫技能目录」。
     *
     * 背景：此前描述只说「当用户请求与某条描述匹配时」加载，属被动等匹配；模型接手任务后
     * 常凭自身记忆直接开干，从不主动扫目录，导致成套的专业做法（含踩过的坑）被闲置。
     * 现要求描述里出现主动检索的动作指引，防止回退成被动措辞。
     */
    @Test
    fun `load_skill description tells model to scan catalog before starting`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "load_skill" }
        val desc = tool.function.description
        assertTrue("描述应要求开工前先扫技能目录：$desc", desc.contains("开工前"))
        assertTrue("描述应指向系统提示末尾的技能目录：$desc", desc.contains("可用技能"))
        assertTrue("描述应给出加载动作：$desc", desc.contains("load_skill") || desc.contains("本工具"))
    }

    /**
     * 回归（P1）：`load_skill` 原样返回整篇 systemPrompt。目录扫描注册的 SKILL.md 无体积上限
     * （实测 10 万字符级），而当前轮工具结果受整轮保护、连 413 强制折叠都砍不掉，
     * 小窗口模型上单轮请求必超限。注入侧与按需加载侧现在共用同一把尺。
     */
    @Test
    fun `load_skill body is clipped to the same budget as injection`() {
        val huge = "x".repeat(108_000)
        val clipped = clipSkillBody(huge)
        assertTrue("超长正文必须被截断：${clipped.length}", clipped.length < huge.length)
        assertTrue("截断后不得超出单技能正文上限", clipped.length <= 8_000 + 120)
        assertTrue("截断应带可见标注与指引", clipped.contains("已按上下文预算截断"))
        assertTrue(clipped.contains("资源目录"))

        val small = "y".repeat(100)
        assertEquals("未超限的正文必须原样返回", small, clipSkillBody(small))
    }
}
