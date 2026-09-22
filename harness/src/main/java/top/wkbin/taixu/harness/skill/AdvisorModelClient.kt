package top.wkbin.taixu.harness.skill

import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ChatResult
import top.wkbin.taixu.harness.ModelConfig

/**
 * 技能进化顾问所需的**最小**模型客户端面。
 *
 * 存在的原因：顾问原先直接依赖 `ProviderClient`（具体类，构造链要 OkHttpClient +
 * ProviderRepository + AiModelRepository + McpManager），测试里无法廉价 fake，
 * 于是"触发点"整条链路零覆盖——冷却被失败路径烧掉、digest 读错源、删会话留孤儿行
 * 这些缺陷全都测不到。
 *
 * 生产绑定见 `app/di/AdvisorModelModule`（`@Binds` 到 `ProviderClient`）。
 */
interface AdvisorModelClient {
    suspend fun resolveConfigured(modelId: String? = null, modelVariant: String? = null): ModelConfig

    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult
}
