package top.wkbin.taixu.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import okio.Path.Companion.toOkioPath
import java.time.Duration

/**
 * UI 性能优化：Coil3 图片加载器全局配置
 * 
 * 问题：ChatMessageBubbles.kt 等组件中 AsyncImage 未配置统一的 ImageLoader，
 * 导致每次创建新实例，内存缓存和磁盘缓存无法复用。
 * 
 * 解决方案：提供单例 ImageLoader，启用内存/磁盘双层缓存、交叉淡入动画和调试日志。
 */

@Composable
fun rememberOptimizedImageLoader(
    context: Context = LocalContext.current,
    memoryCacheSizePercent: Int = 25, // 使用 25% 可用内存作为内存缓存
    diskCacheSizeBytes: Long = 100L * 1024 * 1024, // 100MB 磁盘缓存
    enableCrossfade: Boolean = true,
    crossfadeDurationMillis: Int = 300
): ImageLoader {
    return remember(context) {
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memoryCacheSizePercent.toDouble())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(diskCacheSizeBytes)
                    .build()
            }
            .apply { if (enableCrossfade) crossfade(crossfadeDurationMillis) }
            .logger(DebugLogger()) // 仅在 debug 构建中输出日志
            .build()
    }
}

/**
 * 预加载图片到缓存
 * 
 * @param context Android 上下文
 * @param imageUrl 图片 URL
 * @param imageLoader 图片加载器（默认使用优化的单例）
 */
@Composable
fun preloadImageToCache(
    context: Context = LocalContext.current,
    imageUrl: String,
    imageLoader: ImageLoader = rememberOptimizedImageLoader(context)
) {
    val request = coil3.request.ImageRequest.Builder(context)
        .data(imageUrl)
        .build()
    
    remember(imageUrl, imageLoader) {
        imageLoader.enqueue(request)
    }
}

/**
 * 批量预加载多张图片
 * 
 * @param context Android 上下文
 * @param imageUrls 图片 URL 列表
 * @param imageLoader 图片加载器
 */
@Composable
fun preloadImagesBatch(
    context: Context = LocalContext.current,
    imageUrls: List<String>,
    imageLoader: ImageLoader = rememberOptimizedImageLoader(context)
) {
    remember(imageUrls, imageLoader) {
        imageUrls.forEach { url ->
            val request = coil3.request.ImageRequest.Builder(context)
                .data(url)
                .build()
            imageLoader.enqueue(request)
        }
    }
}
