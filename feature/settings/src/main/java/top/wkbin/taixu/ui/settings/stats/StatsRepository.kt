package top.wkbin.taixu.ui.settings.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.model.StatsDateRange
import top.wkbin.taixu.core.model.StatsHeatmapDay
import top.wkbin.taixu.core.model.StatsRankItem
import top.wkbin.taixu.core.model.StatsSnapshot
import top.wkbin.taixu.core.model.StatsSummary
import top.wkbin.taixu.core.model.StatsTokenBucket
import top.wkbin.taixu.core.model.StatsTrendDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val runtimeRepository: HarnessRuntimeRepository,
    private val sessionRepository: HarnessSessionRepository,
    private val aiModelRepository: AiModelRepository,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun buildSnapshot(
        range: StatsDateRange,
        now: LocalDate = LocalDate.now(),
    ): StatsSnapshot = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startEpochMs = range.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val endEpochMs = range.end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

        // 1. 基础汇总
        val totalSessions = sessionRepository.countInRange(startEpochMs, endEpochMs)
        val totalMessages = runtimeRepository.countEntriesInRange(startEpochMs, endEpochMs)
        val launchCount = settingsDataStore.appLaunchCount.first()

        // 2. 所有模型与会话缓存
        val allModels = aiModelRepository.observeAll().first().associateBy { it.id }
        val allSessions = sessionRepository.listAll().associateBy { it.id }

        // 3. 用量聚合 —— 关键改动（2026-09-14 OOM 修复）
        //
        // 原实现：runtimeRepository.listEntriesInRange(startEpochMs, endEpochMs) 把区间内**全部**
        // harness_entries（含完整 payloadJson）读进内存，再对每条 json.parseToJsonElement 建树。
        // 实测库内 15,888 条（tool_result 5,696 条，单体可达数十 KB~数 MB），
        // 选「全部时间」时直接把 256MB 堆打爆 → java.lang.OutOfMemoryError。
        //
        // 现改为 SQL 层聚合：json_extract 在 SQLite 侧取标量、按 (sessionId, customType) GROUP BY，
        // 1.5 万行压缩成几十行小结果返回，Kotlin 侧不再接触 payloadJson 本体。
        val usageRows = runtimeRepository.aggregateUsageInRange(startEpochMs, endEpochMs)

        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var totalCachedTokens = 0L

        val modelUsageCounts = mutableMapOf<String, Int>()
        val providerUsageCounts = mutableMapOf<String, Int>()
        val assistantUsageCounts = mutableMapOf<String, Int>()

        // 趋势图聚合桶
        val trendStart = if (range.isAllTime) now.minusDays(29) else (range.start ?: now.minusDays(29))
        val trendEnd = if (range.isAllTime) now else (range.end ?: now)
        val trendBuckets = mutableMapOf<LocalDate, MutableMap<String, StatsTokenBucket>>()

        var dayCursor = trendStart
        while (!dayCursor.isAfter(trendEnd)) {
            trendBuckets[dayCursor] = mutableMapOf()
            dayCursor = dayCursor.plusDays(1)
        }

        // 话题会话排行用的每会话累计条数（由聚合行累加，无需原始消息）
        val sessionEntryCounts = mutableMapOf<String, Int>()

        for (row in usageRows) {
            val session = allSessions[row.sessionId]
            val sessionModelId = session?.modelId
            val modelEntity = sessionModelId?.let { allModels[it] }

            val providerName = modelEntity?.provider?.ifBlank { "默认" } ?: "内置"
            val modelName = modelEntity?.name ?: sessionModelId ?: "通用助手"

            // 统计助手/工作区维度
            val workspaceLabel = session?.workspace?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')?.ifBlank { null }
                ?: session?.title?.takeIf { it.isNotBlank() }
                ?: "默认工程"
            assistantUsageCounts[workspaceLabel] = (assistantUsageCounts[workspaceLabel] ?: 0) + row.entryCount
            sessionEntryCounts[row.sessionId] = (sessionEntryCounts[row.sessionId] ?: 0) + row.entryCount

            when (row.customType) {
                "user" -> {
                    // user 文本不在 SQL 侧取出，改用字符数估算（与原 estimateTokens 同口径量级）
                    totalInputTokens += estimateTokensFromChars(row.textChars)
                }
                "assistant" -> {
                    val finalPrompt = row.promptTokens
                    val finalOutput = if (row.completionTokens > 0L) {
                        row.completionTokens
                    } else {
                        estimateTokensFromChars(row.textChars + row.reasoningChars).toLong()
                    }
                    totalInputTokens += finalPrompt
                    totalOutputTokens += finalOutput
                    totalCachedTokens += row.cachedTokens

                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + row.entryCount
                    providerUsageCounts[providerName] = (providerUsageCounts[providerName] ?: 0) + row.entryCount
                }
                "tool_call" -> {
                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + row.entryCount
                }
            }
        }

        // 趋势桶：按天聚合（同样不取 payloadJson）
        val dailyRows = runtimeRepository.aggregateDailyCounts(startEpochMs, endEpochMs)
        for (row in dailyRows) {
            val msgDate = Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate()
            if (msgDate.isBefore(trendStart) || msgDate.isAfter(trendEnd)) continue
            val session = allSessions[row.sessionId]
            val providerName = session?.modelId?.let { allModels[it] }?.provider?.ifBlank { "默认" } ?: "内置"
            val dayMap = trendBuckets.getOrPut(msgDate) { mutableMapOf() }
            val bucket = dayMap[providerName] ?: StatsTokenBucket()
            dayMap[providerName] = bucket.add(activity = row.entryCount)
        }

        // 4. 热力图（近 180 天打卡矩阵）—— 同样用按天聚合，不加载原始条目
        val heatmapStartEpoch = now.minusDays(180).atStartOfDay(zone).toInstant().toEpochMilli()
        val rawHeatmapRows = runtimeRepository.aggregateDailyCounts(heatmapStartEpoch, null)
            .filter { it.createdAt > 0L }
            .groupingBy {
                runCatching {
                    Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate().toString()
                }.getOrDefault("")
            }
            .fold(0) { acc, item -> acc + item.entryCount }

        val heatmapDays = mutableListOf<StatsHeatmapDay>()
        var hCursor = now.minusDays(180)
        while (!hCursor.isAfter(now)) {
            val dateStr = hCursor.toString()
            val count = rawHeatmapRows[dateStr] ?: 0
            heatmapDays.add(StatsHeatmapDay(date = hCursor, count = count))
            hCursor = hCursor.plusDays(1)
        }

        // 5. 话题会话排行（用聚合出的每会话条目数，替代原先对全量消息分组）
        val topicRankRows = sessionEntryCounts.entries.sortedByDescending { it.value }.take(20)
        val topicRank = topicRankRows.map {
            StatsRankItem(
                id = it.key,
                label = allSessions[it.key]?.title?.ifBlank { "未命名会话" } ?: "未命名会话",
                value = it.value,
            )
        }

        // 6. 模型排行
        val modelRank = modelUsageCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { StatsRankItem(id = it.key, label = it.key, value = it.value) }

        // 7. 助手/工作区排行
        val assistantRank = assistantUsageCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { StatsRankItem(id = it.key, label = it.key, value = it.value) }

        // 8. 趋势数据组装
        val trendList = trendBuckets.entries.map { (date, map) ->
            StatsTrendDay(date = date, providerTokens = map)
        }

        StatsSnapshot(
            range = range,
            summary = StatsSummary(
                totalConversations = totalSessions,
                totalMessages = totalMessages,
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cachedTokens = totalCachedTokens,
                launchCount = launchCount,
            ),
            heatmap = heatmapDays,
            trend = trendList,
            modelRank = modelRank,
            assistantRank = assistantRank,
            topicRank = topicRank,
        )
    }

    /**
     * 由「字符数」估算 token —— 供 SQL 聚合路径使用。
     *
     * 原实现按字符逐个数权重（CJK 记 2、ASCII 记 1）再乘 0.75；SQL 侧 LENGTH() 只能拿到字符数、
     * 拿不到原文，故这里直接用字符数近似：按 1 字符 ≈ 1 权重保守估计，再乘同一个 0.75 系数，
     * 保证与逐字符版本同量级、不产生数量级偏差。
     */
    private fun estimateTokensFromChars(chars: Long): Long {
        if (chars <= 0L) return 0L
        return (chars * 0.75).toLong().coerceAtLeast(1L)
    }
}
