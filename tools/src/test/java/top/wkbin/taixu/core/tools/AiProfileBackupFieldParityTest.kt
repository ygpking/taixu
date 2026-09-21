package top.wkbin.taixu.core.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.AiModelProfileExport

/**
 * 模型档案备份的**字段完整性**回归测试。
 *
 * 背景（真实缺陷）：`AiModelProfileExport` 曾漏掉 `inputTokenLimit` /
 * `compactionKeepRecentTokens` / `compactionReserveTokens` 三个字段，而实体里有。
 * 备份文件含 API key，本身就是换机/重装的迁移通道 → 用户导一次、再导入，
 * 三项上下文调优静默变 null（回退全局推导），界面上没有任何提示。
 *
 * 这里用两道断言钉住：
 *  1. 序列化层：三个字段必须真的写进 JSON 并能被读回（导出结构漏字段即失败）；
 *  2. 结构层：实体与导出结构必须**同时**声明这些字段 —— 新增调优字段时
 *     如果只加实体忘了加导出，这道断言会先失败（而不是等用户丢配置）。
 */
class AiProfileBackupFieldParityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 必须随备份一起迁移的每模型上下文调优字段（实体 ↔ 导出同名）。 */
    private val tuningFields = listOf(
        "inputTokenLimit",
        "compactionKeepRecentTokens",
        "compactionReserveTokens",
    )

    @Test
    fun `tuning fields survive serialization and parsing`() {
        val export = AiModelProfileExport(
            name = "测试档案",
            provider = "Custom",
            model = "gpt-test",
            contextTokens = 200_000,
            inputTokenLimit = 120_000,
            compactionKeepRecentTokens = 20_000,
            compactionReserveTokens = 4_000,
        )
        val raw = json.encodeToString(AiModelProfileExport.serializer(), export)
        tuningFields.forEach { field ->
            assertTrue("导出 JSON 必须包含 $field", raw.contains("\"$field\""))
        }

        // 与 AiProfileBackupCodec.parseProfiles 同一套 Json 配置：单档案形态读回
        val profile = json.decodeFromString<AiModelProfileExport>(raw)
        assertEquals(120_000, profile.inputTokenLimit)
        assertEquals(20_000, profile.compactionKeepRecentTokens)
        assertEquals(4_000, profile.compactionReserveTokens)
    }

    @Test
    fun `entity and export declare the same tuning fields`() {
        val entityFields = AiModelEntity::class.java.declaredFields.map { it.name }.toSet()
        val exportFields = AiModelProfileExport::class.java.declaredFields.map { it.name }.toSet()
        tuningFields.forEach { field ->
            assertTrue("实体缺少 $field（字段被改名？）", entityFields.contains(field))
            assertTrue(
                "导出结构缺少 $field —— 备份会静默丢这项配置（本次回归的根因）",
                exportFields.contains(field),
            )
        }
    }
}
