package top.wkbin.taixu.harness.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * 🪣 Token Bucket 限流器
 * 
 * 功能：
 * 1. 限制单位时间内的操作次数（如子代理递归调用、API 请求）
 * 2. 支持突发流量（burst）
 * 3. 线程安全（Mutex 保护）
 * 4. 可配置 refillRate（每秒补充 token 数）和 capacity（最大容量）
 * 
 * 用法示例：
 * ```kotlin
 * val limiter = TokenBucket(refillRate = 10, capacity = 50)
 * 
 * // 尝试获取 token，失败则等待
 * limiter.acquire()
 * 
 * // 或带超时的获取
 * try {
 *     limiter.acquire(timeout = 5.seconds)
 * } catch (e: TimeoutException) {
 *     // 处理超时
 * }
 * ```
 */
class TokenBucket(
    private val refillRate: Int = 10,        // 每秒补充的 token 数
    private val capacity: Int = 50,          // 最大容量（突发上限）
    private val initialTokens: Int = capacity // 初始 token 数
) {
    @Volatile
    private var tokens: Double = initialTokens.toDouble()
    
    private val mutex = Mutex()
    private var lastRefillTime = System.nanoTime()

    /**
     * 尝试获取一个 token，如果不足则等待
     * 
     * @param timeout 最长等待时间，null 表示无限等待
     * @throws TimeoutException 如果超时仍未获取到 token
     */
    suspend fun acquire(timeout: Duration? = null) {
        val startTime = System.nanoTime()
        
        while (true) {
            val available = mutex.withLock {
                refill()
                val current = tokens
                if (current >= 1.0) {
                    tokens = current - 1.0
                    true
                } else {
                    false
                }
            }
            
            if (available) {
                return
            }
            
            // 检查超时
            timeout?.let { t ->
                val elapsed = Duration.nanoseconds(System.nanoTime() - startTime)
                if (elapsed >= t) {
                    throw TimeoutException("Token bucket acquisition timed out after ${elapsed.toString()}")
                }
            }
            
            // 计算需要等待的时间
            val waitTime = mutex.withLock {
                refill()
                val needed = 1.0 - tokens
                if (needed <= 0) return@withLock 0L
                
                // 计算补充所需 token 需要的时间（毫秒）
                val waitMs = (needed / refillRate * 1000).toLong().coerceAtLeast(1)
                waitMs.coerceAtMost(100) // 单次等待不超过 100ms
            }
            
            delay(waitTime)
        }
    }

    /**
     * 尝试获取多个 token，立即返回是否成功
     * 
     * @param count 需要的 token 数量
     * @return 是否成功获取
     */
    suspend fun tryAcquire(count: Int = 1): Boolean {
        return mutex.withLock {
            refill()
            if (tokens >= count) {
                tokens -= count
                true
            } else {
                false
            }
        }
    }

    /**
     * 获取当前可用 token 数
     */
    suspend fun availableTokens(): Double {
        return mutex.withLock {
            refill()
            tokens
        }
    }

    /**
     * 补充 token（根据经过的时间计算）
     */
    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillTime
        val elapsedSeconds = Duration.nanoseconds(elapsedNanos).toDouble(DurationUnit.SECONDS)
        
        val toAdd = elapsedSeconds * refillRate
        tokens = (tokens + toAdd).coerceAtMost(capacity.toDouble())
        
        if (toAdd > 0) {
            lastRefillTime = now
        }
    }

    /**
     * 重置 token 桶到初始状态
     */
    suspend fun reset() {
        mutex.withLock {
            tokens = initialTokens.toDouble()
            lastRefillTime = System.nanoTime()
        }
    }
}

/**
 * Token 获取超时异常
 */
class TimeoutException(message: String) : Exception(message)

/**
 * 递归深度限制器
 * 
 * 功能：
 * 1. 限制递归调用的最大深度
 * 2. 线程安全（ThreadLocal 存储当前深度）
 * 3. 自动清理（使用 try-finally 确保深度递减）
 * 
 * 用法示例：
 * ```kotlin
 * val depthLimiter = RecursionDepthLimiter(maxDepth = 5)
 * 
 * fun recursiveCall(level: Int) {
 *     depthLimiter.checkAndEnter()
 *     try {
 *         // 执行递归逻辑
 *         if (level < maxLevel) {
 *             recursiveCall(level + 1)
 *         }
 *     } finally {
 *         depthLimiter.exit()
 *     }
 * }
 * ```
 */
class RecursionDepthLimiter(
    private val maxDepth: Int = 5
) {
    private val currentDepth = ThreadLocal<Int>().apply { set(0) }

    /**
     * 检查并进入下一层递归
     * 
     * @throws RecursionDepthExceededException 如果超过最大深度
     */
    fun checkAndEnter() {
        val depth = currentDepth.get()!!
        if (depth >= maxDepth) {
            throw RecursionDepthExceededException(
                "Maximum recursion depth ($maxDepth) exceeded. Current depth: $depth"
            )
        }
        currentDepth.set(depth + 1)
    }

    /**
     * 退出当前递归层
     */
    fun exit() {
        val depth = currentDepth.get()!!
        currentDepth.set(depth - 1)
    }

    /**
     * 获取当前递归深度
     */
    fun getCurrentDepth(): Int {
        return currentDepth.get()!!
    }

    /**
     * 重置递归深度计数器
     */
    fun reset() {
        currentDepth.set(0)
    }
}

/**
 * 递归深度超限异常
 */
class RecursionDepthExceededException(message: String) : Exception(message)

/**
 * 组合限流器：同时限制速率和递归深度
 * 
 * 用法示例：
 * ```kotlin
 * val subagentLimiter = CombinedLimiter(
 *     rateLimit = TokenBucket(refillRate = 10, capacity = 50),
 *     depthLimit = RecursionDepthLimiter(maxDepth = 5)
 * )
 * 
 * suspend fun invokeSubagent() {
 *     subagentLimiter.enter()
 *     try {
 *         // 执行子代理逻辑
 *     } finally {
 *         subagentLimiter.exit()
 *     }
 * }
 * ```
 */
class CombinedLimiter(
    private val rateLimit: TokenBucket,
    private val depthLimit: RecursionDepthLimiter
) {
    /**
     * 进入受保护的代码块（检查速率和深度）
     */
    suspend fun enter() {
        rateLimit.acquire()
        depthLimit.checkAndEnter()
    }

    /**
     * 退出受保护的代码块
     */
    fun exit() {
        depthLimit.exit()
    }
}
