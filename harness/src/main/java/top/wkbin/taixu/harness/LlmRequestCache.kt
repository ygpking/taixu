package top.wkbin.taixu.harness

/**
 * 非流式 chat 的请求缓存（对齐 DeepSeek Harness 的 request-cache）：
 * 相同请求（模型 + 消息）在 TTL 内命中缓存，避免重复请求、更快且省 token。
 *
 * 只缓存非流式调用——流式有副作用（增量已推给 UI），且中途取消/重试语义复杂，不可安全复用。
 *
 * 用法要点（移植自 Wanxiang 时修正的一处 bug）：
 * 本类**必须作为 [ProviderClient]（@Singleton）的常驻成员注入**，让多次请求共享同一实例。
 * 若像 Wanxiang 原实现那样把它 new 在每次都会重建的 [ChatApi] 上，
 * 则每次请求都拿到一个全新空缓存，命中率恒为 0，等于没做缓存。
 */
internal class LlmRequestCache(
    private val maxEntries: Int = 32,
    private val ttlMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val result: ChatResult, val expiresAt: Long)

    private val map = LinkedHashMap<String, Entry>()
    // 命中计数（只增，供指标层读取，供量化验证「流式缓存是否真的命中」）。
    @Volatile private var _hitCount = 0

    @Synchronized
    fun get(key: String): ChatResult? {
        val entry = map.remove(key) ?: return null
        if (clock() > entry.expiresAt) return null
        map[key] = entry // 重新插入末尾，实现 LRU 最近使用
        _hitCount++
        return entry.result
    }

    @Synchronized
    fun put(key: String, result: ChatResult) {
        map.remove(key)
        map[key] = Entry(result, clock() + ttlMillis)
        while (map.size > maxEntries) {
            map.remove(map.keys.iterator().next())
        }
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size

    /** 本地请求缓存累计命中次数（跨 get 调用只增）；供指标层量化「缓存是否真的在命中」。 */
    val hitCount: Int @Synchronized get() = _hitCount
}
