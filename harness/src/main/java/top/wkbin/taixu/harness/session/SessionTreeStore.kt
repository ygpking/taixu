package top.wkbin.taixu.harness.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import java.util.concurrent.ConcurrentHashMap

/** Serialization and active-branch projection for the immutable session tree. */
@Singleton
class SessionTreeStore @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val logger: AppLogger,
) {
    private val laneLocks = ConcurrentHashMap<String, Mutex>()

    /** 仅供测试断言"会话删除后锁缓存确实被回收"；生产代码不应读取。 */
    internal val laneLockCountForTest: Int get() = laneLocks.size

    private fun laneLock(sessionId: String, laneName: String): Mutex =
        // computeIfAbsent 而非 getOrPut：stdlib 的 getOrPut 是 get→null→invoke→put
        // 四步（无 putIfAbsent/computeIfAbsent 语义），两个并发首访者会各建一把 Mutex，
        // map 只留后一把——互斥当场失效，moveLane 与 appendToLane 可互相覆盖分支游标。
        laneLocks.computeIfAbsent("$sessionId$LANE_LOCK_SEPARATOR$laneName") { Mutex() }

    suspend fun ensureMainLane(sessionId: String) {
        repository.ensureLane(sessionId, MAIN_LANE)
    }

    /** 当前 lane 的叶子条目 id（无 lane 或空 lane 时为 null）。 */
    suspend fun laneLeafId(sessionId: String, laneName: String = MAIN_LANE): String? =
        runCatching { repository.findLane(sessionId, laneName)?.leafId }.getOrNull()

    suspend fun load(sessionId: String, laneName: String = MAIN_LANE): List<HarnessMessage> = runCatching {
        val lane = repository.ensureLane(sessionId, laneName)
        repository.branchTail(sessionId, lane.leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        logger.e("Failed to load harness branch for $sessionId/$laneName: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun loadAt(sessionId: String, leafId: String?): List<HarnessMessage> = runCatching {
        repository.branchTail(sessionId, leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        logger.e("Failed to load harness branch at $sessionId/$leafId: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun append(sessionId: String, message: HarnessMessage, laneName: String = MAIN_LANE) {
        laneLock(sessionId, laneName).withLock {
            val lane = repository.ensureLane(sessionId, laneName)
            val entry = HarnessEntryEntity(
                id = message.id,
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = message.createdAt,
                entryType = "message",
                customType = messageType(message),
                payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
            )
            repository.appendToLane(sessionId, laneName, entry)
        }
    }

    /** Navigate to the parent of [entryId], preserving the abandoned branch. */
    suspend fun rewindBefore(sessionId: String, entryId: String, laneName: String = MAIN_LANE) {
        laneLock(sessionId, laneName).withLock {
            val target = repository.findEntry(sessionId, entryId) ?: return
            repository.moveLane(sessionId, laneName, target.parentId)
        }
    }

    suspend fun moveTo(sessionId: String, entryId: String?, laneName: String = MAIN_LANE) {
        laneLock(sessionId, laneName).withLock {
            repository.moveLane(sessionId, laneName, entryId)
        }
    }

    suspend fun deleteSession(sessionId: String) {
        // 会话删除必须同时清掉按 sessionId 缓存的 lane 锁：key 形如 "$sessionId/$laneName"，
        // 每删一个会话就会永久留下它的锁对象（Mutex 本身不再被任何协程引用，却仍被 map 强引用），
        // 而 id 是 UUID，会话永不复用 → 纯泄漏，且随会话数线性增长。
        // HarnessLoop.deleteSession 已清理 sessionJobs / sessionMutexes / messageProjector /
        // stateMirrors 等十余个同类容器，此处补齐漏网的这一处。
        laneLocks.keys.removeAll { it.startsWith("$sessionId$LANE_LOCK_SEPARATOR") }
        repository.deleteSessionData(sessionId)
    }

    /**
     * 检索命中项：附带该消息在**活动分支中的原始索引**。
     *
     * 该索引与 [read] 的 `index` 参数是同一套编号；此前的调用方只能拿到「命中结果序号」，
     * 与 [read] 的原始索引口径不一致，模型照抄序号会读到错误的消息。
     */
    data class SearchHit(val message: HarnessMessage, val index: Int)

    suspend fun search(sessionId: String, query: String, limit: Int = 8): List<HarnessMessage> =
        searchIndexed(sessionId, query, limit).map { it.message }

    /** 返回活动分支中的原始索引，与 [read] 使用同一套编号。 */
    suspend fun searchIndexed(sessionId: String, query: String, limit: Int = 8): List<SearchHit> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        val resultsByCallId = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
        val callsById = messages.filterIsInstance<ToolCall>().associateBy { it.id }
        val terms = needle.split(SEARCH_TERM_SEPARATOR).filter { it.isNotBlank() }

        return messages.withIndex().mapNotNull { (index, message) ->
            val relatedText = when (message) {
                is ToolCall -> resultsByCallId[message.id].orEmpty().joinToString("\n") { searchableText(it) }
                is ToolResult -> callsById[message.toolCallId]?.let(::searchableText).orEmpty()
                else -> ""
            }
            val haystack = searchableText(message) + "\n" + relatedText
            val exactMatch = haystack.contains(needle, ignoreCase = true)
            val matchedTerms = terms.count { haystack.contains(it, ignoreCase = true) }
            if (!exactMatch && (terms.isEmpty() || matchedTerms != terms.size)) null
            else SearchMatch(message, index, if (exactMatch) matchedTerms + 2 else matchedTerms)
        }.sortedWith(compareByDescending<SearchMatch> { it.score }.thenByDescending { it.index })
            .take(limit.coerceIn(1, 20))
            .map { SearchHit(it.message, it.index) }
    }

    suspend fun read(sessionId: String, messageId: String? = null, index: Int? = null): HarnessMessage? {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        return when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        }
    }

    /** Read one active-branch message and keep a tool call/result exchange together. */
    suspend fun readWithRelated(
        sessionId: String,
        messageId: String? = null,
        index: Int? = null,
    ): List<HarnessMessage> {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        val selected = when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        } ?: return emptyList()
        val relatedIds = when (selected) {
            is ToolCall -> messages.filterIsInstance<ToolResult>()
                .filter { it.toolCallId == selected.id }
                .mapTo(mutableSetOf(selected.id)) { it.id }
            is ToolResult -> mutableSetOf(selected.id, selected.toolCallId)
            else -> mutableSetOf(selected.id)
        }
        return messages.filter { it.id in relatedIds }
    }

    internal fun decode(entity: HarnessEntryEntity): HarnessMessage? {
        if (entity.entryType != "message") return null
        return runCatching { json.decodeFromString(HarnessMessage.serializer(), entity.payloadJson) }
            .onFailure { logger.w("Skipping invalid harness entry ${entity.id}: ${it.message}") }
            .getOrNull()
    }

    private fun messageType(message: HarnessMessage): String = when (message) {
        is UserMessage -> "user"
        is AssistantText -> "assistant"
        is ToolCall -> "tool_call"
        is ToolResult -> "tool_result"
        is CapabilityEvent -> "capability_event"
        is ModelSwitchEvent -> "model_switch"
        is SkillSuggestion -> "skill_suggestion"
    }

    private fun searchableText(message: HarnessMessage): String = when (message) {
        is CapabilityEvent -> "${message.kind} ${message.name} ${message.details}"
        is SkillSuggestion -> "${message.skillName} ${message.description}"
        is ModelSwitchEvent -> "${message.fromLabel} ${message.toLabel}"
        is UserMessage -> message.text
        is AssistantText -> "${message.text}\n${message.reasoning.orEmpty()}"
        is ToolCall -> "${message.rawToolName.orEmpty()} ${message.tool} ${message.args} ${message.reasoning.orEmpty()}"
        is ToolResult -> message.output
    }

    companion object {
        const val MAIN_LANE = "main"

        /**
         * lane 锁 key 的分隔符。删除会话时按 `"$sessionId$LANE_LOCK_SEPARATOR"` 前缀批量清理，
         * 定义成常量避免「生成 key」与「清理 key」两处拼写漂移后静默漏删。
         */
        const val LANE_LOCK_SEPARATOR = "/"
        /** Maximum decoded messages retained per live UI/session projection. */
        const val MAX_LIVE_ENTRIES = 600
        private val SEARCH_TERM_SEPARATOR = Regex("[\\s,，;；|]+")
    }

    private data class SearchMatch(val message: HarnessMessage, val index: Int, val score: Int)
}
