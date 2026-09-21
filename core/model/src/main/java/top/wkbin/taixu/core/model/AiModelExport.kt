package top.wkbin.taixu.core.model

import kotlinx.serialization.Serializable

/**
 * 模型档案单项配置导出/导入数据结构
 */
@Serializable
data class AiModelProfileExport(
    val id: String? = null,
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String? = null,
    val apiKeys: List<String> = emptyList(),
    val requestsPerMinutePerKey: Int = 0,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val reasoningMode: String? = null,
    val reasoningEffort: String? = null,
    val toolCallMode: String? = null,
    val contextTokens: Int? = null,
    /**
     * 每轮输入裁切基准（回答"我每轮主动裁到多少"）。
     *
     * 必须进导出结构：它与 [compactionKeepRecentTokens] / [compactionReserveTokens]
     * 一起构成用户对单个模型的上下文调优。备份文件含 API key，本身就是换机/重装的
     * 迁移通道——漏映射等于让用户无声丢配置（导入后回退全局推导，界面上没有任何提示）。
     */
    val inputTokenLimit: Int? = null,
    /** 压缩后保留窗口的 token 上限；null 表示用全局默认。 */
    val compactionKeepRecentTokens: Int? = null,
    /** 压缩时为输出与工具 schema 预留的 token 数；null 表示用全局默认。 */
    val compactionReserveTokens: Int? = null,
    val customHeaders: String = "",
    val pureChatMode: Boolean = false,
    val visionEnabled: Boolean = true,
    val imageGenerationEnabled: Boolean = false,
    val responseApiEnabled: Boolean = false,
)

/**
 * 模型档案批量导出/导入数据包容器
 */
@Serializable
data class AiModelProfileBundle(
    val schemaVersion: Int = 1,
    val exportedAt: Long = 0L,
    val source: String = "TaiXu",
    val profiles: List<AiModelProfileExport> = emptyList(),
)
