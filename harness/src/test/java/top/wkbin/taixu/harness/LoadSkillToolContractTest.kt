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
        assertTrue(tool.function.description.contains("按需加载"))
    }
}
