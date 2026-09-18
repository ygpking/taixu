package top.wkbin.taixu.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * UI 性能优化：Lazy 列表分页加载扩展
 * 
 * 问题：ChatScreen.kt、HomeScreen.kt 等使用 LazyColumn/LazyRow 时，
 * 一次性加载所有数据，长列表时内存占用高、首屏渲染慢。
 * 
 * 解决方案：提供 Paging3 集成模板和懒加载占位符，支持增量加载和预取。
 */

/**
 * LazyColumn with Paging3 support template
 * 
 * 使用方法：
 * ```
 * val pagingItems = viewModel.messagesFlow.collectAsLazyPagingItems()
 * LazyColumnWithPaging(
 *     pagingItems = pagingItems,
 *     itemContent = { message ->
 *         MessageItem(message)
 *     }
 * )
 * ```
 */
@Composable
fun <T : Any> LazyColumnWithPaging(
    modifier: Modifier = Modifier,
    pagingItems: androidx.paging.compose.LazyPagingItems<T>,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    enablePlaceholders: Boolean = true,
    placeholderCount: Int = 5,
    itemContent: @Composable (item: T?) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(
            count = pagingItems.itemCount
        ) { index ->
            val item = pagingItems[index]
            
            // 可选：显示占位符骨架屏
            if (item == null && enablePlaceholders) {
                LoadingPlaceholderItem()
            } else {
                itemContent(item)
            }
        }
        
        // 底部加载状态指示器
        when {
            pagingItems.loadState.append is androidx.paging.LoadState.Loading -> {
                item { LoadingIndicator() }
            }
            pagingItems.loadState.append is androidx.paging.LoadState.Error -> {
                item {
                    val error = pagingItems.loadState.append as androidx.paging.LoadState.Error
                    ErrorRetryItem(error = error.error.localizedMessage ?: "Unknown error") {
                        pagingItems.retry()
                    }
                }
            }
        }
    }
}

/**
 * LazyRow with Paging3 support template
 */
@Composable
fun <T : Any> LazyRowWithPaging(
    modifier: Modifier = Modifier,
    pagingItems: androidx.paging.compose.LazyPagingItems<T>,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    enablePlaceholders: Boolean = true,
    itemContent: @Composable (item: T?) -> Unit
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(
            count = pagingItems.itemCount
        ) { index ->
            val item = pagingItems[index]
            
            if (item == null && enablePlaceholders) {
                LoadingPlaceholderItem(horizontal = true)
            } else {
                itemContent(item)
            }
        }
        
        when {
            pagingItems.loadState.append is androidx.paging.LoadState.Loading -> {
                item { LoadingIndicator() }
            }
            pagingItems.loadState.append is androidx.paging.LoadState.Error -> {
                item {
                    val error = pagingItems.loadState.append as androidx.paging.LoadState.Error
                    ErrorRetryItem(error = error.error.localizedMessage ?: "Unknown error") {
                        pagingItems.retry()
                    }
                }
            }
        }
    }
}

/**
 * 默认每页加载 20 条消息（适用于聊天场景）
 * 可根据实际数据量调整：图片流建议 10-15 条，纯文本建议 30-50 条
 */
const val DEFAULT_PAGE_SIZE = 20

/**
 * 预取阈值：距离末尾还有 5 条时开始加载下一页
 */
const val DEFAULT_PREFETCH_DISTANCE = 5

/**
 * 加载占位符项（骨架屏）
 */
@Composable
fun LoadingPlaceholderItem(horizontal: Boolean = false) {
    // TODO: 实现骨架屏组件
    // 建议使用 ShimmerEffect + RoundedRectangle 创建动画占位符
    // 参考：https://github.com/valentinwilk/shimmer-compose
}

/**
 * 底部加载指示器
 */
@Composable
fun LoadingIndicator() {
    // TODO: 使用 RuntimeCircularProgressIndicator
    // 注意：添加 padding 避免贴底
}

/**
 * 错误重试项
 */
@Composable
fun ErrorRetryItem(error: String, onRetry: () -> Unit) {
    // TODO: 显示错误信息和重试按钮
    // 建议使用 RuntimeCard + RuntimeButton 组合
}

/**
 * 为 ViewModel 提供的 PagingSource 工厂函数模板
 * 
 * 在 ViewModel 中使用：
 * ```
 * val messagesFlow = Pager(
 *     config = PagingConfig(
 *         pageSize = DEFAULT_PAGE_SIZE,
 *         prefetchDistance = DEFAULT_PREFETCH_DISTANCE,
 *         enablePlaceholders = true
 *     ),
 *     pagingSourceFactory = { MessagesPagingSource(repository) }
 * ).flow.cachedIn(viewModelScope)
 * ```
 */
// TODO: 在 core/data 模块中实现 PagingSource 基类
