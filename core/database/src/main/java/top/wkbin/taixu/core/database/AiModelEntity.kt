package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Harness 可用的模型配置。密钥只存引用（Android Keystore 别名），
 * 明文 Key 永不落库。
 */
@Entity(tableName = "harness_models")
data class AiModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val baseUrl: String = "",
    val secretRef: String = "",
    val isActive: Boolean = false,
    val createdAt: Long,
    /** 推理参数（null = 使用服务端默认）：温度，0.0 ~ 2.0。 */
    val temperature: Float? = null,
    /** 推理参数（null = 使用服务端默认）：单次回复最大 token 数。 */
    val maxTokens: Int? = null,
    /** 推理参数（null = 使用服务端默认）：核采样阈值，0.0 ~ 1.0。 */
    val topP: Float? = null,
    /** 推理开关：null = auto（跟随模型默认）；"disabled" / "enabled"。 */
    val reasoningMode: String? = null,
    /** 推理强度：null = 默认；"low" / "medium" / "high"。 */
    val reasoningEffort: String? = null,
    /**
     * 工具调用模式：null = native（OpenAI 标准函数调用）；
     * "json" = JSON 文本格式（工具列表写入系统提示词，模型用文本输出工具调用）；
     * "disabled" = 禁用工具（纯聊天）。
     */
    val toolCallMode: String? = null,
    /** 上下文 Token 容量上限（如 128000，超出时自动滑动窗口压缩，null = 默认）。 */
    val contextTokens: Int? = null,
    /**
     * 单次输入上限（token）：每轮请求主动裁切到的目标水位，null = 未显式配置（按窗口推导）。
     *
     * 与 [contextTokens] 语义**不同**，不可混用：
     *  - `contextTokens` 回答「整个窗口能装多大」（模型能力声明，不参与裁切）；
     *  - `inputTokenLimit` 回答「我每轮主动裁到多少」（**裁切基准**）。
     *
     * 历史缺陷：裁切基准直接取 `contextTokens`，用户填 1_000_000 后折叠触发线升到 ~98.7 万，
     * 历史堆到 38 万也不折叠 → 上游 HTTP 413 反复。修法见 [ContextBudgetDefaults.resolveInputLimit]。
     */
    val inputTokenLimit: Int? = null,
    /** 自定义请求头（多行 Key: Value 格式，请求时追加注入）。 */
    val customHeaders: String = "",
    /** 纯净排查模式：关闭太墟系统提示词与工具定义注入，仅发送纯用户消息。 */
    val pureChatMode: Boolean = false,
    /** 是否支持视觉多模态直接传图（true = 直接以 image_url 发送；false = 提示工具读取）。 */
    val visionEnabled: Boolean = true,
    /** 是否支持生成图片。仅用于明确的模型能力与生图交互，默认关闭以避免误判文本模型。 */
    val imageGenerationEnabled: Boolean = false,
    /** 是否使用 Responses API（true = /v1/responses；false = /v1/chat/completions）。 */
    val responseApiEnabled: Boolean = false,
    /** 已配置的 API Key 数量（仅元数据；Key 明文始终位于加密存储）。 */
    val apiKeyCount: Int = 0,
    /** 单个 Key 每分钟最多发起的请求数；0 表示不做客户端限制。 */
    val requestsPerMinutePerKey: Int = 0,
    /**
     * 每模型压缩预算覆盖（对齐 pi compaction.modelOverrides）：
     * 压缩触发时保留的最近 token 上限；null = 不启用收紧（跟随全局预算行为）。
     */
    val compactionKeepRecentTokens: Int? = null,
    /** 压缩预算线中为 LLM 响应预留的 token；null = 使用内置默认。 */
    val compactionReserveTokens: Int? = null,
)

@Dao
interface AiModelDao {
    @Query("SELECT * FROM harness_models ORDER BY isActive DESC, createdAt ASC")
    fun observeAll(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM harness_models WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AiModelEntity?

    @Query("SELECT * FROM harness_models WHERE isActive = 1 LIMIT 1")
    suspend fun activeModel(): AiModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: AiModelEntity)

    @Query("UPDATE harness_models SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE harness_models SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    /**
     * 独占激活：清空现有活跃标记后把 [id] 置为活跃。
     *
     * 调用方原先分两次调用 [clearActive] + [setActive]（无事务）。两步之间若进程被杀 /
     * 协程被取消 / 另一处并发写入插入，会停在「全部非活跃」的中间态：
     * 此后 `activeModel()` 返回 null，Harness 侧取不到默认模型
     * （表现为"模型选择被重置 / 回退内置"），而依赖
     * 「当前是否存在活跃档案」做分支的写入逻辑也会因此走上不同路径。
     * 收进 @Transaction 后两步原子可见。仓库内 AgentSubagentDao.replace/syncBuiltinCatalog
     * 已是同一模式（见 AgentSubagentEntity.kt:103/120）。
     */
    @Transaction
    suspend fun activateOnly(id: String) {
        // 目标不存在时直接返回：否则 clearActive() 会留下"全部非活跃"的终态
        // （activeModel() == null），比中间态更糟——它不会自己恢复。
        // 典型场景：调用方拿到的 id 刚被删除（并发删除档案 / 导入失败回滚）。
        if (findById(id) == null) return
        clearActive()
        setActive(id)
    }

    /** 独占激活并同时写入档案本体（用于「切到本地模型」这类清零+upsert 的组合）。 */
    @Transaction
    suspend fun activateExclusively(model: AiModelEntity) {
        clearActive()
        upsert(model)
    }

    /**
     * 条件式独占写：只在 [clearOthers] 为真时先清空活跃标记，再写入 [model]。
     *
     * 覆盖 `AiProfileWriter.upsertProfile` 的语义 —— 它只在
     * 「当前没有任何活跃档案」或「正在编辑当前活跃档案」时才需要清空，
     * 其余情况应保留既有活跃项。把「判断 + 清空 + 写入」三步收进同一事务，
     * 避免判断依据（`observeAll().first()`）与写入之间被别的写入插队。
     */
    @Transaction
    suspend fun upsertKeepingOrReplacingActive(model: AiModelEntity, clearOthers: Boolean) {
        if (clearOthers) clearActive()
        upsert(model)
    }

    @Query("UPDATE harness_models SET reasoningMode = :mode, reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoning(id: String, mode: String?, effort: String?)

    @Query("DELETE FROM harness_models WHERE id = :id")
    suspend fun delete(id: String)
}
