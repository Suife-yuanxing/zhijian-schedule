package com.zhijian.schedule.core.ui.metal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/** 承载 RuntimeShader 的 Brush：draw 阶段更新 uniform，复用同一 shader 实例 */
internal class MetalShaderBrush(
    private val shader: Shader,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/**
 * 一个元件表面的实时交互状态：**触摸点 + 按压深度**。
 *
 * 这两个量直接喂进着色器：触摸点决定光斑位置与凹陷中心，按压深度决定凹陷深度与光斑亮度。
 * 用 Animatable 做弹簧（而不是瞬时值），是因为「金属被指腹压下去再弹回来」这件事本身
 * 有物理时长——瞬间到位读作廉价。用户要求极致效果，所以这里的弹簧比常规按压更长一点。
 */
class MetalTouchState internal constructor() {
    /** 按压目标 0..1：指针事件只写这个（不挂起），弹簧由 rememberMetalTouchState 驱动 */
    internal val pressTarget = mutableFloatStateOf(0f)
    internal val touchX = mutableFloatStateOf(0.5f)
    internal val touchY = mutableFloatStateOf(0.5f)
    internal val pressAnim = Animatable(0f)

    /** 当前归一化触摸点（0..1）；未被触摸时停在最后一次的位置，避免光斑跳回中心 */
    val touch: Offset
        get() = Offset(touchX.floatValue, touchY.floatValue)

    val pressValue: Float
        get() = pressAnim.value
}

/**
 * 触摸状态 + 弹簧驱动。
 *
 * 按压不是瞬时到位：金属被指腹压下去、松手弹回来，本身有物理时长。
 * 弹簧参数按「按下要脆、松开要软」分档（用户要求极致效果，这档比常规按压更长一点）。
 */
@Composable
fun rememberMetalTouchState(): MetalTouchState {
    val state = remember { MetalTouchState() }
    val target = state.pressTarget.floatValue
    LaunchedEffect(target) {
        val down = target > 0.5f
        state.pressAnim.animateTo(
            target,
            spring(
                dampingRatio = if (down) 0.55f else 0.42f,
                stiffness = if (down) 900f else 520f,
            ),
        )
    }
    return state
}

/**
 * 把指针事件接到 [MetalTouchState]：移动即时更新触摸点（1:1，不加缓动），
 * 按下/抬起只写目标值，弹簧交给 [rememberMetalTouchState]。
 */
fun Modifier.metalPointer(state: MetalTouchState): Modifier =
    this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val w = size.width.coerceAtLeast(1).toFloat()
                val h = size.height.coerceAtLeast(1).toFloat()
                when (event.type) {
                    PointerEventType.Move, PointerEventType.Enter -> {
                        // 直接写值：跟手的光斑不需要缓动，加了反而「黏」
                        state.touchX.floatValue = (change.position.x / w).coerceIn(0f, 1f)
                        state.touchY.floatValue = (change.position.y / h).coerceIn(0f, 1f)
                    }
                    PointerEventType.Press -> {
                        state.touchX.floatValue = (change.position.x / w).coerceIn(0f, 1f)
                        state.touchY.floatValue = (change.position.y / h).coerceIn(0f, 1f)
                        state.pressTarget.floatValue = 1f
                    }
                    PointerEventType.Release, PointerEventType.Exit -> {
                        state.pressTarget.floatValue = 0f
                    }
                }
            }
        }
    }

/**
 * 金属元件表面（Brush 路径）。
 *
 * - A13+ / 非低端机：AGSL 球冠法线 + 程序化摄影棚 + 菲涅尔 + 拉丝高光 + 薄膜虹彩 + 触摸凹陷；
 * - 旧机：多层渐变降档（哑光底 + 倒角亮圈 + 一道斜向拉丝 + 按压暗角），保留「金属的明暗结构」。
 *
 * [chrome] 抛光程度覆盖：卡面用低值（哑光为主），按钮/浮岛用高值（镜面为主）。
 */
@Composable
fun Modifier.metalSurface(
    shape: RoundedCornerShape,
    touch: MetalTouchState? = null,
    clock: State<Float>? = null,
    chromeOverride: Float? = null,
    faceOverride: Color? = null,
): Modifier {
    val spec = LocalCoursePreset.current.metal ?: return this
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    val supported = remember(context) { MetalRuntime.supported(context) }
    val touchTint = if (isDark) spec.tintDark else spec.tintLight
    val face = faceOverride ?: if (isDark) spec.faceDark else spec.faceLight
    val chrome = chromeOverride ?: spec.chrome

    if (supported && clock != null) {
        val shader = remember(spec) { MetalRuntime.surfaceShader(spec) }
        val brush = remember(shader) { MetalShaderBrush(shader) }
        return this.drawBehind {
            val r = shape.radiusPx(size, this)
            MetalRuntime.updateSurface(
                shader = shader,
                widthPx = size.width,
                heightPx = size.height,
                radiusPx = r,
                time = clock.value,
                touch = touch?.touch ?: Offset(0.5f, 0.5f),
                press = touch?.pressValue ?: 0f,
                tint = touchTint,
                face = face,
                chrome = chrome,
            )
            drawRoundRect(brush = brush, cornerRadius = CornerRadius(r, r))
        }
    }

    // ---- 降档：无 shader 也必须有「金属的明暗结构」，而不是一块平色 ----
    return this.drawBehind {
        val r = shape.radiusPx(size, this)
        val cr = CornerRadius(r, r)
        val press = touch?.pressValue ?: 0f
        val tp = touch?.touch ?: Offset(0.5f, 0.5f)
        val w = size.width
        val h = size.height

        drawRoundRect(color = face, cornerRadius = cr)
        // 球冠明暗：上亮下暗（光从上方来），中心略亮
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.White.copy(alpha = 0.20f + 0.10f * press),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.22f),
                        ),
                    startY = 0f,
                    endY = h,
                ),
            cornerRadius = cr,
        )
        // 斜向拉丝：一条贯穿的柔光带
        drawRoundRect(
            brush =
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                ),
            cornerRadius = cr,
        )
        // 触摸光斑
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f + 0.28f * press), Color.Transparent),
                    center = Offset(tp.x * w, tp.y * h),
                    radius = h * (0.9f + 0.5f * press),
                ),
            radius = h * (0.9f + 0.5f * press),
            center = Offset(tp.x * w, tp.y * h),
        )
        // 倒角：内亮外暗两圈，这是「金属片有厚度」的唯一线索
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.12f)),
                ),
            cornerRadius = cr,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.18f),
            cornerRadius = cr,
            style = Stroke(width = 1f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** 圆角半径（px）：百分比圆角（胶囊）按「短边一半」算，绝对值圆角按 dp 换算 */
internal fun RoundedCornerShape.radiusPx(
    size: Size,
    density: androidx.compose.ui.unit.Density,
): Float = topStart.toPx(size, density)
