package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentContextDao {

    // ========== 长期记忆 (Memory) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): AgentMemoryEntity?

    @Query("SELECT * FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId LIMIT 1")
    suspend fun getMemoryByKey(key: String, scope: String, ownerId: String): AgentMemoryEntity?

    /** 按主题键（subjectKey）定位同主题记忆，用于冲突去重与 revision 判定。 */
    @Query("SELECT * FROM agent_memories WHERE subjectKey = :subjectKey AND scope = :scope AND ownerId = :ownerId LIMIT 1")
    suspend fun getMemoryBySubjectKey(subjectKey: String, scope: String, ownerId: String): AgentMemoryEntity?

    /** 钉选记忆：总是注入 system prompt 稳定前缀，绕过检索与新鲜度过滤。 */
    @Query("""
        SELECT * FROM agent_memories
        WHERE pinned = 1
          AND ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
        ORDER BY updatedAt DESC, id ASC
    """)
    suspend fun getPinnedMemories(projectOwnerId: String, sessionId: String): List<AgentMemoryEntity>

    /** 未过期记忆（新鲜度查询基础）：expiresAt 为 null 或晚于 now 视为新鲜。 */
    @Query("""
        SELECT * FROM agent_memories
        WHERE ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
          AND pinned = :pinned
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getFreshMemories(projectOwnerId: String, sessionId: String, pinned: Boolean, now: Long, limit: Int): List<AgentMemoryEntity>

    /** 续期：确认记忆仍然有效，刷新 lastVerifiedAt（新鲜度信号，不删除）。 */
    @Query("UPDATE agent_memories SET lastVerifiedAt = :now WHERE id = :id AND (expiresAt IS NULL OR expiresAt > :now)")
    suspend fun touchMemory(id: String, now: Long)

    @Query("""
        SELECT * FROM agent_memories
        WHERE (scope = 'global' AND ownerId = '')
           OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
           OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId)
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
    """)
    suspend fun getMemoriesForContext(projectOwnerId: String, sessionId: String, limit: Int): List<AgentMemoryEntity>

    @Query("SELECT COUNT(*) FROM agent_memories WHERE scope = :scope AND ownerId = :ownerId")
    suspend fun countMemories(scope: String, ownerId: String): Int

    @Query("SELECT * FROM agent_memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<AgentMemoryEntity>>

    @Query("""
        SELECT * FROM agent_memories
        WHERE ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
          AND (`key` LIKE '%' || :queryEscaped || '%' ESCAPE '\'
            OR `value` LIKE '%' || :queryEscaped || '%' ESCAPE '\')
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
    """)
    suspend fun searchMemoriesEscaped(
        queryEscaped: String,
        projectOwnerId: String,
        sessionId: String,
        limit: Int,
    ): List<AgentMemoryEntity>

    /**
     * 记忆检索。[query] 会先经 [escapeLikePattern] 转义，因此 `%`、`_`、`\` 按字面匹配——
     * 模型常直接传路径或符号串（含 `_`），不转义时召回集会偏离预期。
     * 转义放在 Kotlin 侧而非 SQL 里：SQL 侧replace 三层嵌套既难读也难测。
     */
    suspend fun searchMemories(
        query: String,
        projectOwnerId: String,
        sessionId: String,
        limit: Int,
    ): List<AgentMemoryEntity> =
        searchMemoriesEscaped(escapeLikePattern(query), projectOwnerId, sessionId, limit)

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    /**
     * 按键删除记忆，返回实际删除的行数。
     *
     * 返回值是必需的：原先返回 Unit，调用方只能无条件回「已删除」——key 不存在时
     * 模型会误认为删除生效。
     */
    @Query("DELETE FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId")
    suspend fun deleteMemoryByKey(key: String, scope: String, ownerId: String): Int

    // ========== 任务规划 (Plan) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: AgentPlanEntity)

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getPlanBySession(sessionId: String): AgentPlanEntity?

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId AND status = 'active' LIMIT 1")
    suspend fun getActivePlan(sessionId: String): AgentPlanEntity?

    /**
     * 活跃计划的响应式查询：agent_plans 表任何写入（含步骤更新）都会触发重新发射，
     * 供看板实时刷新，避免此前"只在切换会话时读一次、DB 更新后界面不重绘"的问题。
     */
    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId AND status = 'active' LIMIT 1")
    fun observeActivePlan(sessionId: String): kotlinx.coroutines.flow.Flow<AgentPlanEntity?>

    @Query("DELETE FROM agent_plans WHERE sessionId = :sessionId")
    suspend fun deletePlanBySession(sessionId: String)

    /**
     * 删除某会话产生、且**生命周期绑定该会话**的记忆。
     *
     * 判据：`scope = 'session'`（owner 即 sessionId）。`global` / `project` 的 owner 不是会话 id，
     * 它们的语义就是"跨会话"，**不能**被单次会话删除带走。
     */
    @Query("DELETE FROM agent_memories WHERE scope = 'session' AND ownerId = :sessionId")
    suspend fun deleteSessionScopedMemories(sessionId: String)

    /**
     * 会话删除时的全部上下文数据清理，**单事务**执行。
     *
     * 收进一个 `@Transaction` 而不是让调用方逐个调：三张表要么一起清、要么都不清，
     * 避免"清了两张、第三张漏掉"这种半清理状态（也少一个漏调的机会）。
     */
    @Transaction
    suspend fun deleteSessionContextData(sessionId: String) {
        deletePlanBySession(sessionId)
        clearScratchpads(sessionId)
        deleteSessionScopedMemories(sessionId)
    }

    // ========== 工作草稿 (Scratchpad) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScratchpad(scratchpad: AgentScratchpadEntity)

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key LIMIT 1")
    suspend fun getScratchpad(sessionId: String, key: String): AgentScratchpadEntity?

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun listScratchpads(sessionId: String): List<AgentScratchpadEntity>

    /**
     * 订阅式查询：agent_scratchpads 表任何写入/删除都会触发重新发射。
     *
     * 供 UI 实时刷新，避免此前「combine 后 distinctUntilChanged 把 scratchpadRefresh
     * 增量信号吞掉」导致删除草稿后界面不重绘的问题（与 observeActivePlan 同因同治）。
     */
    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeScratchpads(sessionId: String): kotlinx.coroutines.flow.Flow<List<AgentScratchpadEntity>>

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key")
    suspend fun deleteScratchpad(sessionId: String, key: String)

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId")
    suspend fun clearScratchpads(sessionId: String)
}

/**
 * 转义 SQL LIKE 通配符，使 `%`、`_`、`\` 按字面匹配（配合 `ESCAPE '\'`）。
 *
 * 不做转义时，模型传进来的路径/符号串（`foo_bar`、`a%b`）会被当成通配符，
 * 召回集偏离预期——本文件内 searchBranch 早已这么做，searchMemories 是漏网的那一处。
 */
internal fun escapeLikePattern(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
