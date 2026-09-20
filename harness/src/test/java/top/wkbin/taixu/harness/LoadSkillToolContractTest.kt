package top.wkbin.taixu.harness

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
}
