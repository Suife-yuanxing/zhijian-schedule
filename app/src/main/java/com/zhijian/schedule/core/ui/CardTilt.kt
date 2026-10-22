package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * 卡片的触摸透视倾动。
 *
 * 交互链路：
 * - 按下：光标位置立刻换算成 rotateX / rotateY（不消费事件，点击与滚动照常）；
 * - 拖动：光标在卡片内的位置实时驱动倾角与高光中心（只改绘制，不触发重组）；
 * - 松手：两个轴分别跑阻尼弹簧回正；滚动/翻页抢走手势时同样立即回正。
 *
 * 绘制分工：倾动在 [cardTilt] 的 graphicsLayer 里完成；高光 [cardTiltGlare]
 * 必须放在卡片背景之后、文字之前，这样亮斑不会把正文洗淡。
 */
@Stable
class CardTiltState internal constructor(
    internal val rotationX: Animatable<Float, AnimationVector1D>,
    internal val rotationY: Animatable<Float, AnimationVector1D>,
    internal val glare: Animatable<Float, AnimationVector1D>,
    internal val touch: MutableState<Offset>,
)

/** 主题相关的强度：玻璃主题反光更亮，浅色纸面需要更高的白才能看见。 */
data class CardTiltStyle(
    val maxTiltDegrees: Float,
    val glareAlpha: Float,
)

@Composable
fun rememberCardTiltState(vararg keys: Any?): CardTiltState = remember(*keys) {
    CardTiltState(
        rotationX = Animatable(0f),
        rotationY = Animatable(0f),
        glare = Animatable(0f),
        touch = mutableStateOf(Offset.Unspecified),
    )
}

@Composable
fun rememberCardTiltStyle(): CardTiltStyle {
    val glass = LocalCoursePreset.current.glass != null
    val dark = LocalIsDark.current
    return remember(glass, dark) {
        CardTiltStyle(
            // 9° 足够看出透视，又不至于把大字号周次拉斜得难读
            maxTiltDegrees = if (glass) 9f else 8f,
            glareAlpha = when {
                glass && dark -> 0.44f
                glass -> 0.82f
                dark -> 0.38f
                else -> 0.72f
            },
        )
    }
}

/**
 * 让卡片跟随触摸位置做 3D 透视倾斜，松手用 [Motion.TiltReturn] 阻尼回正。
 *
 * 不消费任何 pointer change：父级 verticalScroll / HorizontalPager 仍能正常滚动；
 * 一旦发现位移被父级消费（滚动接管），本次倾动立即收束。
 */
@Composable
fun Modifier.cardTilt(
    state: CardTiltState,
    shape: Shape,
    style: CardTiltStyle,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    return this
        .pointerInput(state, style.maxTiltDegrees) {
            awaitEachGesture {
                // Initial 阶段先看见按下，但不 consume：clickable 与滚动都还能拿到事件
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.touch.value = down.position
                val downAngles = tiltFor(down.position, size, style.maxTiltDegrees)
                // 手势作用域是受限挂起：stop / snapTo / animateTo 一律交给普通协程
                scope.launch {
                    state.rotationX.stop()
                    state.rotationY.stop()
                    state.glare.stop()
                    state.rotationX.snapTo(downAngles.rotationX)
                    state.rotationY.snapTo(downAngles.rotationY)
                    state.glare.animateTo(1f, tween(durationMillis = Motion.DurTiltGlareIn))
                }

                var canceled = false
                while (true) {
                    // Final 阶段才看得见父级在 Main 阶段留下的 consumed 标记
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.isConsumed && change.position != change.previousPosition) {
                        canceled = true
                        break
                    }
                    if (change.position != change.previousPosition) {
                        state.touch.value = change.position
                        val moveAngles = tiltFor(change.position, size, style.maxTiltDegrees)
                        scope.launch {
                            state.rotationX.snapTo(moveAngles.rotationX)
                            state.rotationY.snapTo(moveAngles.rotationY)
                        }
                    }
                }

                // 高光中心保留在最后落点，让它顺着 alpha 淡出；下次按下会被覆盖
                scope.launch {
                    state.glare.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = if (canceled) Motion.DurTiltGlareIn else Motion.DurTiltGlareOut,
                        ),
                    )
                }
                scope.launch {
                    launch { state.rotationX.animateTo(0f, Motion.TiltReturn) }
                    launch { state.rotationY.animateTo(0f, Motion.TiltReturn) }
                }
            }
        }
        .graphicsLayer {
            rotationX = state.rotationX.value
            rotationY = state.rotationY.value
            // 相机距离必须大于卡片自身尺寸，否则宽卡片旋转时远端会跑到相机后面，
            // 透视被压平甚至出现撕裂。按「最长边 × 1.6」取景，大小卡片观感一致。
            cameraDistance = max(size.width, size.height) * 1.6f
            transformOrigin = TransformOrigin.Center
            this.shape = shape
            clip = true
        }
}

/**
 * 径向反射流光：跟着触摸点走的柔光 + 亮核，用 Screen 混合。
 *
 * 必须排在 `.background(...)` 之后、`.border(...)` / 文字之前：
 * 这样亮斑只提亮卡面，不会盖在课名上影响可读性。
 */
fun Modifier.cardTiltGlare(
    state: CardTiltState,
    shape: Shape,
    style: CardTiltStyle,
    color: Color = Color.White,
): Modifier = drawBehind {
    val intensity = state.glare.value
    if (intensity <= 0.01f) return@drawBehind
    val center = state.touch.value
    if (!center.isSpecified) return@drawBehind

    val outline = shape.createOutline(size, layoutDirection, this@drawBehind)
    val clip = Path().apply { addOutline(outline) }
    clipPath(clip) {
        val peak = style.glareAlpha * intensity
        val longSide = max(size.width, size.height)
        val shortSide = min(size.width, size.height)

        // 外层柔光：铺满卡面的低强度光，让「整张纸被照亮」而不是只多了一个白点
        val broadRadius = longSide * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = peak * 0.42f),
                0.45f to color.copy(alpha = peak * 0.18f),
                1f to Color.Transparent,
                center = center,
                radius = broadRadius,
            ),
            radius = broadRadius,
            center = center,
            blendMode = BlendMode.Screen,
        )

        // 内层亮核：贴着指腹的镜面高光
        val coreRadius = (shortSide * 0.72f).coerceAtLeast(longSide * 0.34f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = peak * 0.90f),
                0.20f to color.copy(alpha = peak * 0.62f),
                0.55f to color.copy(alpha = peak * 0.22f),
                1f to Color.Transparent,
                center = center,
                radius = coreRadius,
            ),
            radius = coreRadius,
            center = center,
            blendMode = BlendMode.Screen,
        )
    }
}

/**
 * 触摸点 → 以卡片中心为原点的 [-1,1]（x 右正、y 下正）。
 * 手指拖出卡片后夹到边缘，倾角不会继续无界增大。
 */
internal fun tiltNormalized(position: Offset, size: Size): Offset {
    if (size.width <= 0f || size.height <= 0f) return Offset.Zero
    val nx = (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
    val ny = (position.y / size.height * 2f - 1f).coerceIn(-1f, 1f)
    return Offset(nx, ny)
}

/** 与 vanilla-tilt 同向：右侧下压时 rotateY 为负，下侧下压时 rotateX 为正。 */
internal fun tiltAngles(normalized: Offset, maxDegrees: Float): TiltAngles {
    val max = maxDegrees.coerceAtLeast(0f)
    val nx = normalized.x.coerceIn(-1f, 1f)
    val ny = normalized.y.coerceIn(-1f, 1f)
    return TiltAngles(
        rotationX = ny * max,
        rotationY = -nx * max,
    )
}

internal data class TiltAngles(val rotationX: Float, val rotationY: Float)

private fun tiltFor(position: Offset, size: IntSize, maxDegrees: Float): TiltAngles {
    val normalized = tiltNormalized(
        position = position,
        size = Size(size.width.toFloat(), size.height.toFloat()),
    )
    return tiltAngles(normalized, maxDegrees)
}
