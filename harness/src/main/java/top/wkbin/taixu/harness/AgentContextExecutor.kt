package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AgentContextRepository
import top.wkbin.taixu.core.database.AgentMemoryEntity
import top.wkbin.taixu.core.database.AgentPlanEntity
import top.wkbin.taixu.core.database.AgentScratchpadEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 负责执行 agent-context 核心系统能力工具：
 * - memory: 长期语义与事实记忆
 * - plan: 多步骤任务执行计划管理
 * - scratchpad: 任务局部工作草稿与排查便签
 */
@Singleton
class AgentContextExecutor @Inject constructor(
    private val agentContextDao: AgentContextRepository,
    private val json: Json,
) {
    suspend fun executeMemory(args: JsonObject, sessionId: String, workspace: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save 必须提供 key 参数"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save 必须提供 value 参数"
                if (key.length > MAX_MEMORY_KEY_CHARS) return false to "memory key 不能超过 $MAX_MEMORY_KEY_CHARS 字符"
                if (value.length > MAX_MEMORY_VALUE_CHARS) return false to "memory value 不能超过 $MAX_MEMORY_VALUE_CHARS 字符"
                val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "fact"
                val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?: if (workspace.isNotBlank()) "project" else "global"
                val ownerId = memoryOwner(scope, sessionId, workspace)
                    ?: return false to "scope 仅支持 global/project/session，且 project/session 必须有对应上下文"
                val subjectKey = args["subjectKey"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: key
                val volatility = args["volatility"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?.takeIf { it in setOf("reference", "project", "user") } ?: "reference"
                val pinned = (args["pinned"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val expiresAt = args["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val now = System.currentTimeMillis()

                // 同主题冲突去重：同 (scope, ownerId, subjectKey) 只有一个活动修订。
                val existing = agentContextDao.getMemoryBySubjectKey(subjectKey, scope, ownerId)
                    ?: agentContextDao.getMemoryByKey(key, scope, ownerId)
                if (existing == null && agentContextDao.countMemories(scope, ownerId) >= MAX_MEMORIES_PER_OWNER) {
                    return false to "[$scope] 记忆已达到上限 $MAX_MEMORIES_PER_OWNER 条，请删除旧记忆后再保存"
                }
                if (pinned) {
                    val pinnedBudget = MAX_PINNED_CHARS - agentContextDao.getPinnedMemories(projectOwner(workspace), sessionId)
                        .filter { it.id != existing?.id }.sumOf { it.value.length }
                    if (value.length > pinnedBudget) {
                        return false to "pinned 记忆总字符已接近上限（~$MAX_PINNED_CHARS），请精简内容或改为非 pinned"
                    }
                }
                val id = existing?.id ?: UUID.randomUUID().toString()
                val valueChanged = existing?.value != value
                agentContextDao.saveMemory(
                    AgentMemoryEntity(
                        id = id,
                        scope = scope,
                        ownerId = ownerId,
                        kind = kind,
                        key = key,
                        value = value,
                        subjectKey = subjectKey,
                        revision = (existing?.revision ?: 0) + if (valueChanged) 1 else 0,
                        pinned = pinned,
                        expiresAt = expiresAt,
                        lastVerifiedAt = existing?.lastVerifiedAt ?: 0,
                        volatility = volatility,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                val conflictNote = if (existing != null && subjectKey != key && existing.key != subjectKey) {
                    " 同主题($subjectKey)去重并升级为 revision=${existing.revision + if (valueChanged) 1 else 0}"
                } else ""
                val savedMessage = if (existing != null) {
                    val revNote = if (valueChanged) {
                        "，升级为 revision=${existing.revision + 1}"
                    } else {
                        "（内容未变化，revision=${existing.revision}）"
                    }
                    "已更新既有长期记忆[$revNote，原值被覆盖][$scope/$kind] $key = $value$conflictNote"
                } else {
                    "已成功存储长期记忆 [$scope/$kind] $key = $value"
                }
                true to savedMessage
            }
            "verify" -> {
                // 新鲜度续期：模型确认记忆仍有效时刷新 lastVerifiedAt。
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val target = when {
                    id != null -> agentContextDao.getMemoryById(id)
                    key != null -> {
                        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                        val ownerId = memoryOwner(scope, sessionId, workspace)
                            ?: return false to "scope 仅支持 global/project/session"
                        agentContextDao.getMemoryByKey(key, scope, ownerId)
                    }
                    else -> return false to "memory verify 需提供 id 或 key"
                } ?: return false to "未找到要核验的记忆"
                agentContextDao.touchMemory(target.id, System.currentTimeMillis())
                true to "已刷新记忆 ${target.key} 的新鲜度（lastVerifiedAt 续期）"
            }
            "query", "search" -> {
                val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory query 必须提供 query 或 key"
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.searchMemories(
                    query = query.take(MAX_MEMORY_QUERY_CHARS),
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                ).filter { includeExpired || isFresh(it, System.currentTimeMillis()) }
                if (results.isEmpty()) {
                    true to "未查询到与 '$query' 相关的记忆"
                } else {
                    val formatted = results.joinToString("\n") { it.render(lineBreak = true) }
                    true to "查询到以下记忆：\n$formatted"
                }
            }
            "list" -> {
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.getFreshMemories(
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                    pinned = false,
                    now = System.currentTimeMillis(),
                    limit = MAX_MEMORY_LIST,
                ).plus(
                    if (includeExpired) agentContextDao.getMemoriesForContext(projectOwner(workspace), sessionId)
                        .filter { !isFresh(it, System.currentTimeMillis()) }
                    else emptyList(),
                ).distinctBy { it.id }
                if (results.isEmpty()) {
                    true to "当前暂无长期记忆"
                } else {
                    val formatted = results.joinToString("\n") { it.render() }
                    true to "已保存的记忆列表：\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                if (id != null) {
                    val memory = agentContextDao.getMemoryById(id)
                        ?: return false to "未找到记忆 id=$id"
                    val visible = when (memory.scope) {
                        "global" -> memory.ownerId.isEmpty()
                        "project" -> memory.ownerId == projectOwner(workspace) && memory.ownerId.isNotBlank()
                        "session" -> memory.ownerId == sessionId && sessionId.isNotBlank()
                        else -> false
                    }
                    if (!visible) return false to "无权删除不属于当前项目或会话的记忆"
                    agentContextDao.deleteMemoryById(id)
                    true to "已删除记忆 id=$id"
                } else if (key != null) {
                    val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                    val ownerId = memoryOwner(scope, sessionId, workspace)
                        ?: return false to "scope 仅支持 global/project/session，且 project/session 必须有对应上下文"
                    val removed = agentContextDao.deleteMemoryByKey(key, scope, ownerId)
                    if (removed <= 0) {
                        return false to "未找到要删除的记忆 [$scope] key=$key（可能已被删除或键名不符）"
                    }
                    true to "已删除记忆 [$scope] key=$key"
                } else {
                    false to "memory delete 需提供 id 或 key"
                }
            }
            else -> false to "未知的 memory 动作: $action"
        }
    }

    private fun memoryOwner(scope: String, sessionId: String, workspace: String): String? = when (scope) {
        "global" -> ""
        "project" -> projectOwner(workspace).takeIf { it.isNotBlank() }
        "session" -> sessionId.trim().takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * 工程标识归一：Windows 下同一路径可能以反斜杠与正斜杠两种写法进入，
     * 只 trimEnd('/') 会让 project scope 的记忆与 pinned 查询互相 miss。
     */
    private fun projectOwner(workspace: String): String =
        workspace.trim().replace('\\', '/').trimEnd('/')

    /** 新鲜度判定：expiresAt 为 null 或晚于 now 视为新鲜（过期只降权，不删除）。 */
    private fun isFresh(memory: AgentMemoryEntity, now: Long): Boolean {
        val expires = memory.expiresAt
        return expires == null || expires > now
    }

    private fun AgentMemoryEntity.render(lineBreak: Boolean = false): String {
        val suffix = buildString {
            if (pinned) append(" pinned")
            if (expiresAt != null && !isFresh(this@render, System.currentTimeMillis())) append(" 已过期")
            append(" v${revision}")
        }
        val separator = if (lineBreak) "\n        " else " "
        return if (lineBreak) {
            "- [${scope}/${kind}] ${key}: ${value}${separator}(volatility=$volatility, freshness=$suffix)"
        } else {
            "- [${scope}/${kind}] ${key}: ${value}$separator(volatility=$volatility, freshness=$suffix)"
        }
    }

    private companion object {
        const val MAX_MEMORY_KEY_CHARS = 128
        /** 步骤状态字段的候选键名，与 UI 的 PlanStepParser 保持一致，避免写入后读不到。 */
        val STATUS_KEY_CANDIDATES = setOf("status", "state", "completed", "isCompleted", "is_completed", "done", "finished")
        /** 视为"已完成"的状态取值。 */
        val COMPLETED_STATUS_TOKENS = setOf("completed", "complete", "done", "finished")
        const val MAX_MEMORY_VALUE_CHARS = 4_096
        const val MAX_MEMORY_QUERY_CHARS = 256
        const val MAX_MEMORIES_PER_OWNER = 100
        const val MAX_MEMORY_LIST = 100
        const val MAX_PINNED_CHARS = 1_500
    }

    suspend fun executePlan(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "get"
        return when (action) {
            "replace_active", "set_active", "create" -> {
                val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "plan replace_active 必须提供 goal 目标描述"
                val stepsElement = args["steps"]
                val stepsJson = stepsElement?.toString() ?: "[]"
                // agent_plans 以 sessionId 为主键，savePlan 是 REPLACE：旧计划被静默覆盖且无历史、
                // 无备份。技能自动匹配一旦误报（把「上下文工程」类技能注入到无关任务），模型会按
                // 技能正文指令"第一时间 replace_active"，用户真实计划就此永久丢失。
                // 同目标改写步骤是正常用法，直接放行；换目标必须先显式确认，把不可逆操作变可见。
                val existing = agentContextDao.getActivePlan(sessionId)
                val confirmed = args["confirm_replace"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.equals("true", ignoreCase = true) == true
                if (existing != null && existing.goal.trim() != goal && !confirmed) {
                    return false to buildString {
                        append("已存在活跃计划，目标：").append(existing.goal).append('\n')
                        append("replace_active 会整体覆盖它且无法恢复。请先确认：\n")
                        append("· 若确需换成新目标，加 confirm_replace=true 重新调用；\n")
                        append("· 若只是调整步骤，用同一个 goal 调用即可保留原目标；\n")
                        append("· 若旧计划已结束，先调用 clear_active。")
                    }
                }
                agentContextDao.savePlan(
                    AgentPlanEntity(
                        sessionId = sessionId,
                        goal = goal,
                        stepsJson = stepsJson,
                        status = "active",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "已成功创建/更新任务执行规划：\n目标：$goal\n步骤：$stepsJson\n\n提示：请按规划推进执行。每个步骤完成时调用 plan(action=\"advance\", ...) 更新步骤状态；遇到阻碍时调用 replace_active 调整方案；全部完成时调用 clear_active。"
            }
            "get_active", "get" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                if (plan == null) {
                    true to "当前会话暂无活跃任务计划"
                } else {
                    true to "当前活跃任务计划：\n目标：${plan.goal}\n步骤：${plan.stepsJson}\n状态：${plan.status}"
                }
            }
            "advance", "update_steps" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                    ?: return false to "当前会话没有可推进的活跃计划，请先用 replace_active 创建"
                val stepsElement = args["steps"]
                // 三种入参，优先级从高到低：
                //  ① steps：整体替换步骤列表（原行为，兼容旧调用）；
                //  ② stepId + stepStatus：只更新某一步的状态，其余步骤原样保留（新增，避免模型必须重传全量）；
                //  ③ 都不传：只更新计划生命周期状态。
                val stepId = args["stepId"]?.jsonPrimitive?.contentOrNull?.trim()
                val stepStatus = args["stepStatus"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: args["step_status"]?.jsonPrimitive?.contentOrNull?.trim()
                val newStepsJson = when {
                    stepsElement != null -> stepsElement.toString()
                    !stepId.isNullOrBlank() -> updateSingleStep(plan.stepsJson, stepId, stepStatus)
                        ?: return false to "未在步骤列表中找到 id=$stepId 的步骤，请用 replace_active 重写步骤或核对 id"
                    else -> plan.stepsJson
                }
                // status 是计划生命周期枚举（active/completed/cancelled），不是自然语言进度描述。
                // 模型常把阶段说明塞进 status，直接写入会导致 getActivePlan 永久找不到记录。
                val requestedStatus = args["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val newStatus = when (requestedStatus) {
                    "completed", "cancelled", "active" -> requestedStatus
                    else -> plan.status
                }
                agentContextDao.savePlan(
                    plan.copy(
                        stepsJson = newStepsJson,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "【规划进度已同步更新】\n状态：$newStatus\n当前步骤：$newStepsJson\n请继续推进后续任务。"
            }
            "clear_active", "clear" -> {
                agentContextDao.deletePlanBySession(sessionId)
                true to "已清空当前会话的任务规划"
            }
            else -> false to "未知的 plan 动作: $action"
        }
    }

    /**
     * 在既有 stepsJson 中就地更新单个步骤的状态，保留其余字段与原有结构。
     *
     * 兼容模型生成的多种步骤结构：
     *  - `id` 可为字符串或数字；也可用 `1` 起始的序号（index）定位；
     *  - 状态字段名可为 status/state/completed/isCompleted/done 等，写入时沿用原字段名，
     *    避免看板解析器读到不一致的键；
     *  - 顶层可能是数组，也可能是 {"steps": [...]} 包装对象。
     *
     * 返回 null 表示未找到目标步骤（调用方据此报错，不静默成功）。
     */
    private fun updateSingleStep(stepsJson: String, stepId: String, stepStatus: String?): String? {
        val root = runCatching { Json.parseToJsonElement(stepsJson) }.getOrNull() ?: return null
        val completed = stepStatus?.lowercase() in COMPLETED_STATUS_TOKENS
        val target = stepId.trim()
        val wantedIndex = target.toIntOrNull()
        var indexRegistry = 0

        fun updateObject(obj: JsonObject): JsonObject? {
            // 定位：优先按 id/stepId 字段匹配；若步骤无 id，或 id 未命中而传参是数字，
            // 则回退到 1 起始的序号定位（兼容 "把第 3 步标记完成" 这类调用）。
            val idValue = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["stepId"]?.jsonPrimitive?.contentOrNull
            val byId = idValue != null && idValue.trim() == target
            val byIndex = !byId && wantedIndex != null && wantedIndex >= 1 && indexRegistry == wantedIndex
            if (!byId && !byIndex) return null
            // 沿用原状态字段名与类型：布尔型字段写布尔值，字符串型字段写字符串，
            // 避免同一对象里出现 "completed":false 与 "status":"completed" 互相矛盾的残留。
            val statusKey = obj.keys.firstOrNull { it in STATUS_KEY_CANDIDATES }
            val updated = obj.toMutableMap()
            val targetPrimitive = statusKey?.let { obj[it] as? JsonPrimitive }
            when {
                targetPrimitive != null && !targetPrimitive.isString ->
                    updated[statusKey] = JsonPrimitive(completed)
                statusKey != null ->
                    updated[statusKey] = JsonPrimitive(if (completed) "completed" else (stepStatus ?: "in_progress"))
                else ->
                    updated["status"] = JsonPrimitive(if (completed) "completed" else (stepStatus ?: "in_progress"))
            }
            return JsonObject(updated)
        }

        fun walkArray(array: JsonArray): Pair<JsonArray?, Boolean> {
            val out = mutableListOf<JsonElement>()
            var changed = false
            for (element in array) {
                if (element is JsonObject) {
                    indexRegistry++
                    val updated = updateObject(element)
                    if (updated != null) { out.add(updated); changed = true } else out.add(element)
                } else {
                    out.add(element)
                }
            }
            return (if (changed) JsonArray(out) else null) to changed
        }

        return when (root) {
            is JsonArray -> walkArray(root).first?.toString()
            is JsonObject -> {
                val stepsEl = root["steps"] ?: return null
                val array = runCatching { stepsEl.jsonArray }.getOrNull() ?: return null
                val (newArray, changed) = walkArray(array)
                if (!changed || newArray == null) null
                else {
                    val updatedRoot = root.toMutableMap()
                    updatedRoot["steps"] = newArray
                    JsonObject(updatedRoot).toString()
                }
            }
            else -> null
        }
    }

    suspend fun executeScratchpad(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save", "set" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save 必须提供 key"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save 必须提供 value"
                // 体积护栏：与 memory 的 4,096 字符口径对齐。无上限时模型可写入任意长 value，
                // list/get 时全量回显进上下文，DB 行也无界增长。
                if (value.length > MAX_SCRATCHPAD_VALUE_CHARS) {
                    return false to "草稿值过长（${value.length} 字符 > $MAX_SCRATCHPAD_VALUE_CHARS 上限），" +
                        "请拆分多次记录或精简内容"
                }
                agentContextDao.saveScratchpad(
                    AgentScratchpadEntity(
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "已记录工作草稿 [$key] = $value"
            }
            "get" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad get 必须提供 key"
                val item = agentContextDao.getScratchpad(sessionId, key)
                if (item == null) {
                    true to "草稿 [$key] 不存在"
                } else {
                    true to "草稿 [$key]：${item.value}"
                }
            }
            "list" -> {
                val list = agentContextDao.listScratchpads(sessionId)
                if (list.isEmpty()) {
                    true to "当前无工作草稿记录"
                } else {
                    val formatted = list.joinToString("\n") { "- [${it.key}]: ${it.value}" }
                    true to "当前工作草稿：\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad delete 必须提供 key"
                agentContextDao.deleteScratchpad(sessionId, key)
                true to "已删除草稿 [$key]"
            }
            "clear" -> {
                agentContextDao.clearScratchpads(sessionId)
                true to "已清空当前任务的所有工作草稿"
            }
            else -> false to "未知的 scratchpad 动作: $action"
        }
    }
}

/**
 * 草稿值体积上限（字符）：与 memory 的 value 上限对齐，防止模型写入任意长 value
 * 后 list/get 全量回显进上下文、DB 行也无界增长。
 */
internal const val MAX_SCRATCHPAD_VALUE_CHARS = 4_096
