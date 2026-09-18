package top.wkbin.taixu.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * UI 性能优化：动画过渡最佳实践
 * 
 * 问题：
 * 1. ChatScreen.kt、GitPanel.kt 等大量使用 AnimatedVisibility/AnimatedContent，
 *    但未针对低频页面禁用动画，导致滚动卡顿。
 * 2. Tab 切换缺少共享元素过渡，体验割裂。
 * 3. 动画曲线不统一，视觉不一致。
 * 
 * 解决方案：提供优化的动画组合函数和性能开关。
 */

/**
 * 快速淡入淡出动画（适用于内容切换）
 * 
 * 性能提示：在低帧率设备上可设置 enableAnimation=false 禁用
 */
@Composable
fun OptimizedCrossfade(
    targetState: Boolean,
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    content: @Composable () -> Unit
) {
    if (enableAnimation) {
        Crossfade(
            targetState = targetState,
            modifier = modifier,
            label = "OptimizedCrossfade"
        ) {
            content()
        }
    } else {
        content()
    }
}

/**
 * 优化的 AnimatedVisibility（支持动画开关）
 * 
 * @param visible 可见性状态
 * @param enableAnimation 是否启用动画（性能敏感场景可关闭）
 * @param fadeIn 进入动画
 * @param fadeOut 退出动画
 */
@Composable
fun OptimizedAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    content: @Composable () -> Unit
) {
    if (enableAnimation) {
        AnimatedVisibility(
            visible = visible,
            modifier = modifier,
            enter = enter,
            exit = exit,
            content = { content() }
        )
    } else if (visible) {
        content()
    }
}

/**
 * 优化的 AnimatedContent（支持自定义过渡）
 * 
 * 使用示例：
 * ```
 * OptimizedAnimatedContent(targetState = currentPage) { page ->
 *     PageContent(page)
 * }
 * ```
 */
@Composable
fun <T : Any> OptimizedAnimatedContent(
    targetState: T,
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        slideInHorizontally { it } + fadeIn() togetherWith
            slideOutHorizontally { -it } + fadeOut()
    },
    content: @Composable (targetState: T) -> Unit
) {
    if (enableAnimation) {
        AnimatedContent(
            targetState = targetState,
            modifier = modifier,
            transitionSpec = transitionSpec,
            label = "OptimizedAnimatedContent",
            content = { content(it) }
        )
    } else {
        content(targetState)
    }
}

/**
 * Tab 切换专用动画（水平滑动 + 淡入淡出）
 * 
 * 适用于 TaiXuNavHost.kt 中的 Tab 切换
 */
@Composable
fun TabSwitchAnimation(
    targetState: Int, // 当前 Tab 索引
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    content: @Composable (tabIndex: Int) -> Unit
) {
    OptimizedAnimatedContent(
        targetState = targetState,
        modifier = modifier,
        enableAnimation = enableAnimation,
        transitionSpec = {
            // 根据方向决定滑动方向
            val direction = if (targetState > initialState) 1 else -1
            slideInHorizontally(initialOffsetX = { direction * it }) + fadeIn() togetherWith
                slideOutHorizontally(targetOffsetX = { -direction * it }) + fadeOut()
        },
        content = content
    )
}

/**
 * 垂直展开/折叠动画（适用于手风琴效果）
 * 
 * 使用示例：
 * ```
 * OptimizedExpandCollapse(expanded = isExpanded) {
 *     ExpandableContent()
 * }
 * ```
 */
@Composable
fun OptimizedExpandCollapse(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    content: @Composable () -> Unit
) {
    OptimizedAnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enableAnimation = enableAnimation,
        enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
        content = content
    )
}

/**
 * 检测系统动画缩放设置
 * 
 * 当用户在开发者选项中关闭动画时，自动禁用所有动画
 */
@Composable
fun isSystemAnimationEnabled(): Boolean {
    // TODO: 通过 Settings.Global 读取 animator_duration_scale
    // 返回 false 如果用户禁用了系统动画
    return true
}

/**
 * 动画性能配置建议：
 * 
 * 1. 高频更新场景（如流式输出、实时进度）：
 *    - 设置 enableAnimation = false
 *    - 使用 derivedStateOf 包装动画触发条件
 * 
 * 2. Tab 切换/页面导航：
 *    - 启用 TabSwitchAnimation 提升体验
 *    - 对低端设备降级为简单淡入淡出
 * 
 * 3. 列表项展开/折叠：
 *    - 使用 OptimizedExpandCollapse
 *    - 限制同时播放的动画数量（如最多 3 个）
 * 
 * 4. 图片加载完成过渡：
 *    - 使用 Coil3 内置 crossfade（已在 ImageLoadingOptimizations.kt 配置）
 *    - 避免额外的 AnimatedContent 包装
 */
