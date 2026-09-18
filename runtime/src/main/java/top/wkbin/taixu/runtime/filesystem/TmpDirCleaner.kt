package top.wkbin.taixu.runtime.filesystem

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.time.Duration.Companion.days

/**
 * 🗑️ 临时目录 LRU 清理器
 * 
 * 功能：
 * 1. 定期清理临时目录下的过期文件
 * 2. 基于 LRU 策略（最后修改时间）
 * 3. 可配置保留天数和最大容量
 * 4. 支持白名单目录/文件跳过清理
 * 
 * 清理策略：
 * - 保留最近 N 天（默认 7 天）内修改的文件
 * - 总大小超过上限（默认 500MB）时，从最旧文件开始删除
 * - 白名单中的文件永不删除
 * 
 * 用法示例：
 * ```kotlin
 * @Inject lateinit var tmpDirCleaner: TmpDirCleaner
 * 
 * // 手动触发清理
 * val cleanedBytes = tmpDirCleaner.clean()
 * 
 * // 或定时调用（如在每日凌晨）
 * lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.STARTED) {
 *         delay(Duration.ofHours(24))
 *         tmpDirCleaner.clean()
 *     }
 * }
 * ```
 */
@Singleton
class TmpDirCleaner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 执行清理操作
     * 
     * @param targetDir 目标目录（默认使用应用缓存目录，Root 设备可使用 /data/local/tmp）
     * @param maxAgeDays 最大保留天数
     * @param maxSizeBytes 最大容量（字节）
     * @param whitelist 白名单（目录/文件路径前缀，匹配则跳过）
     * @return 清理的字节数
     */
    suspend fun clean(
        targetDir: File? = null,
        maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
        maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
        whitelist: List<String> = DEFAULT_WHITELIST
    ): Long {
        return withContext(Dispatchers.IO) {
            val actualTargetDir = targetDir ?: getDefaultTmpDir()
            
            if (!actualTargetDir.exists() || !actualTargetDir.isDirectory) {
                Log.w(TAG, "Target directory does not exist or is not a directory: $actualTargetDir")
                return@withContext 0L
            }

            val cutoffTime = System.currentTimeMillis() - maxAgeDays.days.inWholeMilliseconds
            var totalCleaned = 0L
            var deletedCount = 0
            var skippedCount = 0

            try {
                // 收集所有文件及其元数据
                val files = collectFiles(actualTargetDir, whitelist)
                
                if (files.isEmpty()) {
                    Log.i(TAG, "No files to clean in $targetDir")
                    return@withContext 0L
                }

                // 按最后修改时间排序（最旧在前）
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                // 计算当前总大小
                var currentTotalSize = files.sumOf { it.length() }
                
                Log.i(TAG, "Found ${files.size} files in $targetDir, total size: ${formatSize(currentTotalSize)}")

                // 第一阶段：删除过期文件（超过 maxAgeDays）
                for (file in sortedFiles) {
                    if (file.lastModified() < cutoffTime) {
                        val fileSize = file.length()
                        if (deleteFile(file)) {
                            totalCleaned += fileSize
                            deletedCount++
                            currentTotalSize -= fileSize
                            Log.d(TAG, "Deleted expired file: ${file.absolutePath} (${formatSize(fileSize)})")
                        } else {
                            skippedCount++
                        }
                    }
                }

                // 第二阶段：如果总大小仍超限，继续删除最旧文件
                if (currentTotalSize > maxSizeBytes) {
                    val remainingFiles = files.filter { it.exists() }
                        .sortedBy { it.lastModified() }
                    
                    for (file in remainingFiles) {
                        if (currentTotalSize <= maxSizeBytes) break
                        
                        val fileSize = file.length()
                        if (deleteFile(file)) {
                            totalCleaned += fileSize
                            deletedCount++
                            currentTotalSize -= fileSize
                            Log.d(TAG, "Deleted oversized file: ${file.absolutePath} (${formatSize(fileSize)})")
                        }
                    }
                }

                Log.i(TAG, "Cleanup completed: deleted $deletedCount files, freed ${formatSize(totalCleaned)}, skipped $skippedCount")
                
            } catch (e: IOException) {
                Log.e(TAG, "IO error during cleanup", e)
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied during cleanup", e)
                throw e
            }

            totalCleaned
        }
    }

    /**
     * 递归收集所有文件（排除白名单）
     */
    private fun collectFiles(
        dir: File,
        whitelist: List<String>
    ): List<File> {
        val files = mutableListOf<File>()
        
        val dirEntries = dir.listFiles() ?: return files
        
        for (entry in dirEntries) {
            // 检查白名单
            if (isWhitelisted(entry.absolutePath, whitelist)) {
                Log.d(TAG, "Skipping whitelisted: ${entry.absolutePath}")
                continue
            }
            
            if (entry.isDirectory) {
                // 递归处理子目录
                files.addAll(collectFiles(entry, whitelist))
            } else if (entry.isFile) {
                files.add(entry)
            }
        }
        
        return files
    }

    /**
     * 检查路径是否在白名单中
     */
    private fun isWhitelisted(path: String, whitelist: List<String>): Boolean {
        return whitelist.any { prefix ->
            path.startsWith(prefix) || path.contains(prefix)
        }
    }

    /**
     * 安全删除文件（含异常处理）
     */
    private fun deleteFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.deleteRecursively()
            } else {
                true // 文件已不存在，视为成功
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete file: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * 获取临时目录使用统计
     */
    suspend fun getUsageStats(
        targetDir: File? = null,
        whitelist: List<String> = DEFAULT_WHITELIST
    ): TmpDirUsageStats {
        return withContext(Dispatchers.IO) {
            val actualTargetDir = targetDir ?: getDefaultTmpDir()
            
            if (!actualTargetDir.exists() || !actualTargetDir.isDirectory) {
                return@withContext TmpDirUsageStats(
                    totalSize = 0L,
                    fileCount = 0,
                    oldestFileAge = 0,
                    newestFileAge = 0
                )
            }

            val files = collectFiles(actualTargetDir, whitelist)
            
            if (files.isEmpty()) {
                return@withContext TmpDirUsageStats(
                    totalSize = 0L,
                    fileCount = 0,
                    oldestFileAge = 0,
                    newestFileAge = 0
                )
            }

            val now = System.currentTimeMillis()
            val totalSize = files.sumOf { it.length() }
            val ages = files.map { now - it.lastModified() }
            
            TmpDirUsageStats(
                totalSize = totalSize,
                fileCount = files.size,
                oldestFileAge = ages.maxOrNull() ?: 0,
                newestFileAge = ages.minOrNull() ?: 0
            )
        }
    }

    companion object {
        private const val TAG = "TmpDirCleaner"
        
        /** 默认最大保留天数（7 天） */
        const val DEFAULT_MAX_AGE_DAYS = 7
        
        /** 默认最大容量（500MB） */
        const val DEFAULT_MAX_SIZE_BYTES = 500L * 1024 * 1024 // 500MB
        
        /** 默认白名单（这些目录/文件不会被清理） */
        val DEFAULT_WHITELIST = listOf(
            "/taixu",      // 太墟主目录（相对路径匹配）
            ".keep",       // 占位文件
            "/proot",      // proot 相关
            "/fuse"        // FUSE 挂载点
        )

        /** 格式化文件大小 */
        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
    
    /**
     * 获取默认的临时目录
     * 优先尝试 /data/local/tmp（需要 Root），失败则回退到应用缓存目录
     */
    private fun getDefaultTmpDir(): File {
        // 尝试使用 /data/local/tmp（仅 Root 设备可用）
        val rootTmpDir = File("/data/local/tmp")
        if (rootTmpDir.exists() && rootTmpDir.canWrite()) {
            return rootTmpDir
        }
        
        // 回退到应用缓存目录（无 Root 设备）
        val appCacheDir = context.cacheDir.resolve("tmp")
        if (!appCacheDir.exists()) {
            appCacheDir.mkdirs()
        }
        return appCacheDir
    }
}

/**
 * 临时目录使用统计
 */
data class TmpDirUsageStats(
    val totalSize: Long,          // 总大小（字节）
    val fileCount: Int,           // 文件数量
    val oldestFileAge: Long,      // 最老文件的年龄（毫秒）
    val newestFileAge: Long       // 最新文件的年龄（毫秒）
) {
    /** 总大小（人类可读格式） */
    val formattedSize: String
        get() = TmpDirCleaner.formatSize(totalSize)
    
    /** 最老文件的年龄（天数） */
    val oldestFileAgeDays: Double
        get() = oldestFileAge.toDouble() / (24 * 60 * 60 * 1000)
}
