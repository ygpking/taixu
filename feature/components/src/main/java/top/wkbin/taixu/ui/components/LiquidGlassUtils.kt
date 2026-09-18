package top.wkbin.taixu.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 触控手势侦听器：用于精准捕获触控拖拽起点、位移与抬起事件。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent = drag(
            pointerId = drag.id,
            onDrag = { onDrag(it, it.positionChange()) },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) return dragEvent
        }
    }
}

/**
 * 液态高光交互类：
 * 使用 AGSL 运行时着色器根据触点实时计算径向漫射高光，在手指按下和拖拽时
 * 沿表面渲染流动的光斑，配合弹性物理实现水滴/液态玻璃独特的触感。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;

            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
            """.trimIndent(),
        )
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus,
                )
                shader.apply {
                    val pos = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.18f * progress))
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        pos.x.fastCoerceIn(0f, size.width),
                        pos.y.fastCoerceIn(0f, size.height),
                    )
                }
                drawRect(
                    ShaderBrush(shader.asComposeShader()),
                    blendMode = BlendMode.Plus,
                )
            } else {
                drawRect(
                    Color.White.copy(0.20f * progress),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}

/**
 * 阻尼拖拽与弹性缩放物理动画：
 * 完整对齐 AndroidLiquidGlass 的手势物理表现，提供：
 * - 拖动时指示器的阻尼位移与速度跟踪
 * - 按压、拖动时的 scaleX / scaleY 挤压拉伸（Squash & Stretch）
 * - 松手后的惯性回弹与弹簧物理
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}


/**
 * 液态玻璃效果预设集合。
 * 封装常用的 drawBackdrop 效果配置，避免在每个 Runtime* 组件中重复参数。
 *
 * 使用方式:
 * ```kotlin
 * Modifier.glassCard(backdrop, shape, surfaceColor)
 * Modifier.glassControl(backdrop, shape)
 * Modifier.glassTrack(backdrop, shape)
 * ```
 */
object GlassEffects {
    /**
     * 表面级玻璃效果（卡片、对话框等大面积容器）
     * 中等模糊 + 大折射 + 景深 + 高光 + 外阴影
     */
    fun Modifier.glassCard(
        backdrop: Backdrop,
        shape: () -> Shape,
        surfaceColor: Color = Color.White.copy(alpha = 0.42f),
        blurRadius: Dp = 6.dp,
        lensMin: Dp = 16.dp,
        lensMax: Dp = 32.dp,
        highlightStyle: () -> Highlight? = { Highlight.Default },
        shadowStyle: () -> Shadow? = { Shadow(radius = 6.dp, alpha = 0.12f) },
        innerShadowStyle: () -> InnerShadow? = { InnerShadow(radius = 4.dp, alpha = 0.08f) },
    ): Modifier = drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensMin.toPx(), lensMax.toPx(), depthEffect = true)
        },
        highlight = highlightStyle,
        shadow = shadowStyle,
        innerShadow = innerShadowStyle,
        onDrawSurface = { drawRect(surfaceColor) },
    )

    /**
     * 控件级玻璃效果（按钮、开关、复选框、单选按钮等交互组件）
     * 低模糊 + 中折射 + 景深 + 高光 + 内阴影
     */
    fun Modifier.glassControl(
        backdrop: Backdrop,
        shape: () -> Shape,
        blurRadius: Dp = 2.dp,
        lensMin: Dp = 8.dp,
        lensMax: Dp = 14.dp,
        highlightAlpha: Float = 0.34f,
        innerShadowAlpha: Float = 0.10f,
    ): Modifier = drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensMin.toPx(), lensMax.toPx(), depthEffect = true)
        },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        innerShadow = { InnerShadow(radius = 2.dp, alpha = innerShadowAlpha) },
    )

    /**
     * 轨道级玻璃效果（滑块轨道、进度条底部轨道）
     * 低模糊 + 小折射 + 景深 + 微妙高光
     */
    fun Modifier.glassTrack(
        backdrop: Backdrop,
        shape: () -> Shape,
        surfaceColor: Color = Color.Transparent,
        blurRadius: Dp = 3.dp,
        lensMin: Dp = 4.dp,
        lensMax: Dp = 6.dp,
        highlightAlpha: Float = 0.20f,
    ): Modifier = drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensMin.toPx(), lensMax.toPx(), depthEffect = true)
        },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        shadow = { Shadow(radius = 2.dp, alpha = 0.06f) },
        onDrawSurface = if (surfaceColor != Color.Transparent) {
            { drawRect(surfaceColor) }
        } else null,
    )

    /**
     * 指示器/滑块拇指级玻璃效果（底栏指示器、滑块拇指等小型焦点元素）
     * 低模糊 + 中折射 + 色散 + 景深 + 高光 + 外阴影 + 内阴影
     */
    fun Modifier.glassIndicator(
        backdrop: Backdrop,
        shape: () -> Shape,
        surfaceColor: Color = Color.White.copy(alpha = 0.90f),
        blurRadius: Dp = 2.dp,
        lensMin: Dp = 6.dp,
        lensMax: Dp = 12.dp,
        chromaticAberration: Boolean = true,
        shadowAlpha: Float = 0.16f,
        innerShadowAlpha: Float = 0.14f,
    ): Modifier = drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensMin.toPx(), lensMax.toPx(), chromaticAberration = chromaticAberration, depthEffect = true)
        },
        highlight = { Highlight.Default },
        shadow = { Shadow(radius = 4.dp, alpha = shadowAlpha) },
        innerShadow = { InnerShadow(radius = 3.dp, alpha = innerShadowAlpha) },
        onDrawSurface = { drawRect(surfaceColor) },
    )

    /**
     * 导航栏/顶栏级玻璃效果（半透明宽面板）
     * 中等模糊 + 大折射 + 景深 + 平面高光
     */
    fun Modifier.glassBar(
        backdrop: Backdrop,
        shape: () -> Shape,
        blurRadius: Dp = 10.dp,
        lensMin: Dp = 14.dp,
        lensMax: Dp = 22.dp,
    ): Modifier = drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensMin.toPx(), lensMax.toPx(), depthEffect = true)
        },
        highlight = { Highlight.Plain },
    )
}
