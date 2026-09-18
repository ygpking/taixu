package top.wkbin.taixu.harness

import android.content.Context
import androidx.collection.lruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化 LLM 请求缓存：结合内存 + 磁盘双层缓存
 * 
 * **问题背景**：
 * 1. 原有的 [LlmRequestCache] 仅为内存缓存，应用重启后缓存丢失
 * 2. 每次打开应用时，相同的请求（如系统提示词、常见任务）都要重新请求 LLM
 * 3. 导致启动慢、消耗更多 token、用户体验差
 * 
 * **解决方案**：
 * 1. 内存缓存：快速访问热点数据（TTL 短，容量小）
 * 2. 磁盘缓存：持久化存储，跨会话复用（TTL 长，容量大）
 * 3. 懒加载磁盘读取：不阻塞首次请求
 * 4. LRU 淘汰策略：自动清理过期/最少使用的条目
 * 
 * **缓存键生成**：模型名 + 消息内容哈希（SHA-256）
 * **存储格式**：JSON 序列化，包含时间戳用于过期判断
 * 
 * @param context Android 上下文
 * @param memoryMaxEntries 内存缓存最大条目数（默认 16）
 * @param diskMaxEntries 磁盘缓存最大条目数（默认 100）
 * @param memoryTtlMillis 内存缓存 TTL（默认 5 分钟）
 * @param diskTtlMillis 磁盘缓存 TTL（默认 24 小时）
 * @param cacheDirName 缓存目录名称
 */
@Singleton
class PersistentLlmCache @Inject constructor(
    private val context: Context,
    private val json: Json,
    memoryMaxEntries: Int = 16,
    diskMaxEntries: Int = 100,
    memoryTtlMillis: Long = 5 * 60 * 1000L,  // 5 分钟
    diskTtlMillis: Long = 24 * 60 * 60 * 1000L,  // 24 小时
    cacheDirName: String = "llm_request_cache",
) {
    
    @Serializable
    private data class CacheEntry(
        val content: String?,
        val toolCallsJson: String,
        val reasoningContent: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
        val timestamp: Long,
    )
    
    // 内存缓存层（快速路径）
    private val memoryCache = lruCache<String, CacheEntry>(memoryMaxEntries)
    
    // 磁盘缓存层（慢速路径，持久化）
    private val cacheDir = File(context.cacheDir, cacheDirName).apply { mkdirs() }
    private val diskIndex = mutableMapOf<String, DiskIndexEntry>()
    private var diskIndexLoaded = false
    
    @Serializable
    private data class DiskIndexEntry(
        val fileName: String,
        val timestamp: Long,
    )
    
    /**
     * 从缓存获取结果：先查内存，再查磁盘
     * 
     * @param key 缓存键（由 [generateCacheKey] 生成）
     * @param ttlMillis 自定义 TTL，覆盖默认值
     * @return 缓存的 ChatResult，若不存在或已过期则返回 null
     */
    suspend fun get(key: String, ttlMillis: Long? = null): ChatResult? = withContext(Dispatchers.IO) {
        val effectiveTtl = ttlMillis ?: memoryTtlMillis
        
        // 1. 尝试内存缓存（最快）
        memoryCache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < effectiveTtl) {
                return@withContext deserializeEntry(entry)
            } else {
                memoryCache.remove(key)
            }
        }
        
        // 2. 尝试磁盘缓存（需要加载索引）
        ensureDiskIndexLoaded()
        diskIndex[key]?.let { indexEntry ->
            val file = File(cacheDir, indexEntry.fileName)
            if (file.exists()) {
                val now = System.currentTimeMillis()
                val diskTtl = ttlMillis ?: diskTtlMillis
                if (now - indexEntry.timestamp < diskTtl) {
                    try {
                        val jsonContent = file.readText()
                        val entry = json.decodeFromString<CacheEntry>(jsonContent)
                        // 提升到内存缓存
                        memoryCache.put(key, entry)
                        return@withContext deserializeEntry(entry)
                    } catch (e: Exception) {
                        // 解析失败，删除损坏文件
                        file.delete()
                        diskIndex.remove(key)
                    }
                } else {
                    // 过期，清理
                    file.delete()
                    diskIndex.remove(key)
                }
            } else {
                diskIndex.remove(key)
            }
        }
        
        return@withContext null
    }
    
    /**
     * 将结果写入缓存：同时写入内存和磁盘
     * 
     * @param key 缓存键
     * @param result 要缓存的结果
     */
    suspend fun put(key: String, result: ChatResult) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entry = serializeResult(result, now)
        
        // 1. 写入内存缓存
        memoryCache.put(key, entry)
        
        // 2. 写入磁盘缓存（异步，不阻塞主流程）
        ensureDiskIndexLoaded()
        try {
            val fileName = "${hashKey(key)}.json"
            val file = File(cacheDir, fileName)
            file.writeText(json.encodeToString(entry))
            diskIndex[key] = DiskIndexEntry(fileName, now)
            
            // 3. 清理超出限制的旧条目
            evictExcessDiskEntries()
        } catch (e: Exception) {
            // 磁盘写入失败不影响内存缓存
        }
    }
    
    /**
     * 清除所有缓存（内存 + 磁盘）
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        ensureDiskIndexLoaded()
        diskIndex.values.forEach { indexEntry ->
            File(cacheDir, indexEntry.fileName).delete()
        }
        diskIndex.clear()
        // 删除目录重建
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
    
    /**
     * 生成缓存键：模型名 + 消息内容的 SHA-256 哈希
     */
    fun generateCacheKey(model: ModelConfig, messages: List<ApiMessage>): String {
        val content = "${model.model}|${messages.joinToString("|||") { "${it.role}:${it.content}" }}"
        return hashKey(content)
    }
    
    private fun hashKey(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    
    private fun serializeResult(result: ChatResult, timestamp: Long): CacheEntry {
        return CacheEntry(
            content = result.content,
            toolCallsJson = json.encodeToString(result.toolCalls),
            reasoningContent = result.reasoningContent,
            inputTokens = result.usage.inputTokens,
            outputTokens = result.usage.outputTokens,
            reasoningTokens = result.usage.reasoningTokens,
            cacheReadTokens = result.usage.cacheReadTokens,
            cacheWriteTokens = result.usage.cacheWriteTokens,
            timestamp = timestamp,
        )
    }
    
    private fun deserializeEntry(entry: CacheEntry): ChatResult {
        return ChatResult(
            content = entry.content,
            toolCalls = json.decodeFromString(entry.toolCallsJson),
            reasoningContent = entry.reasoningContent,
            usage = ChatUsage(
                inputTokens = entry.inputTokens,
                outputTokens = entry.outputTokens,
                reasoningTokens = entry.reasoningTokens,
                cacheReadTokens = entry.cacheReadTokens,
                cacheWriteTokens = entry.cacheWriteTokens,
            ),
        )
    }
    
    @Synchronized
    private fun ensureDiskIndexLoaded() {
        if (diskIndexLoaded) return
        
        // 扫描缓存目录构建索引
        cacheDir.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            try {
                val jsonContent = file.readText()
                val entry = json.decodeFromString<CacheEntry>(jsonContent)
                val key = hashKey(file.nameWithoutExtension)
                diskIndex[key] = DiskIndexEntry(file.name, entry.timestamp)
            } catch (e: Exception) {
                // 忽略损坏的文件
                file.delete()
            }
        }
        
        diskIndexLoaded = true
    }
    
    private fun evictExcessDiskEntries() {
        if (diskIndex.size <= 100) return
        
        // 按时间排序，删除最旧的条目
        val sorted = diskIndex.entries.sortedBy { it.value.timestamp }
        val toRemove = sorted.take(sorted.size - 100)
        
        toRemove.forEach { (key, indexEntry) ->
            File(cacheDir, indexEntry.fileName).delete()
            diskIndex.remove(key)
        }
    }
    
    /**
     * 获取缓存统计信息（用于调试/监控）
     */
    suspend fun getStats(): CacheStats = withContext(Dispatchers.IO) {
        ensureDiskIndexLoaded()
        CacheStats(
            memoryEntries = memoryCache.size,
            diskEntries = diskIndex.size,
            totalSizeBytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L,
        )
    }
    
    @Serializable
    data class CacheStats(
        val memoryEntries: Int,
        val diskEntries: Int,
        val totalSizeBytes: Long,
    )
}
