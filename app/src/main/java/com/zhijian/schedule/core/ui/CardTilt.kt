package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 卡片的触摸透视倾动。
 *
 * 交互链路：
 * - 按下：光标位置立刻换算成 rotateX / rotateY，由 [Motion.TiltFollow] 阻尼跟随（不消费事件，
 *   点击与滚动照常）；
 * - 拖动：光标在卡片内的位置实时驱动倾角与反光中心（只改绘制，不触发重组）；
 * - 松手：两个轴分别跑阻尼弹簧回正；滚动/翻页抢走手势时同样立即回正。
 *
 * 绘制分工：倾动 + 抬升在 [cardTilt] 的 graphicsLayer 里完成；反光 [cardTiltGlare]
 * 必须放在卡片背景之后、文字之前，这样亮斑不会把正文洗淡；前景视差 [cardTiltParallax]
 * 加在内容子层上（对齐 Apple 的图层位移表）。
 *
 * 参数取值都有业界对照，改动前先读各自的 kdoc（见 [rememberCardTiltStyle]、[glareGeometry]）。
 */
@Stable
class CardTiltState internal constructor(
    internal val rotationX: Animatable<Float, AnimationVector1D>,
    internal val rotationY: Animatable<Float, AnimationVector1D>,
    /** 0..1 的按下进度：反光不透明度与抬升幅度**共用**它，两者永远同呼吸。 */
    internal val press: Animatable<Float, AnimationVector1D>,
    internal val touch: MutableState<Offset>,
)

/** 主题相关的强度：玻璃主题反光更亮、抬得更高；浅色纸面需要更高的白才能看见。 */
data class CardTiltStyle(
    val maxTiltDegrees: Float,
    val glareAlpha: Float,
    /** 按下时整卡放大的比例（1.012 = 放大 1.2%）。 */
    val liftScale: Float,
    /** 按下时抬离"桌面"的高度，决定投影的浓度。 */
    val liftElevation: Dp,
    /**
     * 高光**背光侧**的压暗强度。
     *
     * 浅色卡面的亮度本来就接近 1.0（实测 250/255 ≈ 0.98），`BlendMode.Screen` 叠白光
     * 在数学上只能推到 ~0.99——实测平均提亮仅 +0.66 级、肉眼读不出来。
     * 业界全息卡的做法正是这样：simeydotme/pokemon-cards-css 的 `.card__glare` 用
     * `radial-gradient(最远角 at 指针, 白 0.8 @10%, 白 0.65 @20%, **黑 0.5 @90%**)`，
     * 尾巴是**黑**的——真实光泽之所以在浅色纸面上可见，靠的是被照亮区周围那圈"背光"的明暗落差，
     * 而不是把白再叠加到白上。
     *
     * 所以这里补一层随距离加深的柔和压暗（Multiply），让光斑"浮"起来。
     * 深色主题不用（Screen 在暗底上本来就够亮，再压暗只会显脏），故取 0f。
     */
    val glareShade: Float,
)

@Composable
fun rememberCardTiltState(vararg keys: Any?): CardTiltState = remember(*keys) {
    CardTiltState(
        rotationX = Animatable(0f),
        rotationY = Animatable(0f),
        press = Animatable(0f),
        touch = mutableStateOf(Offset.Unspecified),
    )
}

@Composable
fun rememberCardTiltStyle(): CardTiltStyle {
    val glass = LocalCoursePreset.current.glass != null
    val dark = LocalIsDark.current
    return remember(glass, dark) {
        CardTiltStyle(
            // 9° 足够看出透视，又不至于把大字号周次拉斜得难读。
            // 业界网页卡片常用 12°(beUI 默认) / 15°(vanilla-tilt 默认)，那是几百像素的小卡；
            // 这里动的是整宽 ~400dp 的焦点卡，等角度的透视畸变远大于小卡，故收敛到 8~9°。
            maxTiltDegrees = if (glass) 9f else 8f,
            glareAlpha = when {
                glass && dark -> 0.44f
                glass -> 0.82f
                dark -> 0.38f
                else -> 0.72f
            },
            // 抬升对齐 Apple FOCUS-01（聚焦项放大 1.05~1.1 + 投影抬升）。
            // 那个量级是给 10 英尺外的大屏卡片；手机上取 1.2%，读成"活了"而不是"长大了"。
            liftScale = if (glass) 0.014f else 0.010f,
            liftElevation = if (glass) 16.dp else 12.dp,
            // 浅色卡面靠"背光压暗"把光斑托出来；深色卡面 Screen 已经够亮，压暗只会显脏。
            // 数值按实测校准：近白底(≈250/255)上 0.05 的 Multiply 最深处约压 12 级，
            // 足够读出明暗落差又不至于像污渍（0.16 会压到 40 级，实测明显偏重）。
            glareShade = if (dark) 0f else 0.05f,
        )
    }
}

/**
 * 让卡片跟随触摸位置做 3D 透视倾斜，松手用 [Motion.TiltReturn] 阻尼回正。
 *
 * **手势归属**：卡片是一块"实体纸片"，指腹落在卡面上就是在摆弄它——一旦拖动超过触摸阈值，
 * 本次手势就归卡片所有，位移被吃掉，父级 verticalScroll 不会同时滚起来（[consumeDrag]）。
 * 这正是主人要的手感：交互卡片时页面不要跟着滑。
 *
 * 为什么不连按下事件一起消费：轻点要照常生效（卡内的"加权绩点"入口是一个 clickable）。
 * 只在**超过触摸阈值之后**才开始消费——纯点击没有位移，永远不越过阈值，子级 clickable
 * 拿到的仍是一串干净事件；一旦越过阈值，子级 clickable 本来也会自行取消，两不冲突。
 *
 * 消费必须发生在 [PointerEventPass.Initial]：这一趟是从祖先到子孙的**第一趟**，
 * 而 verticalScroll（scrollable）在 Main 趟才判断"位移有没有被消费"。在 Initial 趟先吃掉，
 * 它看到的就已经是 consumed，于是不会起步；等到 Main 趟再消费就晚了，那时滚动已经开始。
 *
 * [enabled] 为 false 时整块装饰效果停用（系统"关闭动画"），调用方传 [rememberMotionAllowed]。
 */
@Composable
fun Modifier.cardTilt(
    state: CardTiltState,
    shape: Shape,
    style: CardTiltStyle,
    enabled: Boolean = true,
    consumeDrag: Boolean = true,
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    return this
        .pointerInput(state, style.maxTiltDegrees, consumeDrag) {
            awaitEachGesture {
                // Initial 趟先看见按下；不消费，clickable 与滚动都还能拿到这串事件
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.touch.value = down.position
                followTilt(state, tiltFor(down.position, size, style.maxTiltDegrees), scope)
                scope.launch { state.press.animateTo(1f, tween(durationMillis = Motion.DurTiltGlareIn)) }

                var dragging = false
                while (true) {
                    // 一直在 Initial 趟上跑：赶在 scrollable 的 Main 趟之前决定这串位移归谁
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.position == change.previousPosition) continue

                    state.touch.value = change.position
                    followTilt(state, tiltFor(change.position, size, style.maxTiltDegrees), scope)

                    if (!dragging &&
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        dragging = true
                    }
                    // 越过阈值后才开始吃位移：轻点不受影响，拖动则把滚动挡在卡片外
                    if (dragging && consumeDrag) change.consume()
                }

                // 高光中心保留在最后落点，让它顺着 alpha 淡出；下次按下会被覆盖
                scope.launch {
                    state.press.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = Motion.DurTiltGlareOut),
                    )
                }
                // 回正弹簧继承拖动末尾的速度：甩一下松手会带着惯性过冲，再稳稳落回
                followTilt(state, TiltAngles(0f, 0f), scope, Motion.TiltReturn)
            }
        }
        .graphicsLayer {
            rotationX = state.rotationX.value
            rotationY = state.rotationY.value
            // 相机距离必须大于卡片自身尺寸，否则宽卡片旋转时远端会跑到相机后面，
            // 透视被压平甚至出现撕裂。按「最长边 × 1.6」取景，大小卡片观感一致。
            //
            // 这个系数与业界网页的 `perspective: 1000px` 等价：1000px 配 ~300px 的小卡是
            // 3.3 倍短边；本式对 960×380 的卡给出 1536px = 4.0 倍短边，同一档观感。
            cameraDistance = max(size.width, size.height) * 1.6f
            transformOrigin = TransformOrigin.Center
            // 抬升：按下时整卡微微放大并离面，投影随之变浓，读成"被指腹抬起来"
            val lift = state.press.value
            val scale = 1f + style.liftScale * lift
            scaleX = scale
            scaleY = scale
            shadowElevation = style.liftElevation.toPx() * lift
            this.shape = shape
            clip = true
        }
}

/**
 * 前景视差：越"浮"在上面的内容位移越大，对齐 Apple tvOS 的图层位移表
 * （背景 1~2pt、中景 3~5pt、前景 5~8pt——前景动得最多才有深度）。
 *
 * 位移方向与倾角同向：压住的那一侧是抬起来的一侧，浮在上面的内容自然往那边滑。
 * 由旋转量反推归一化坐标（nx = -rotationY / max，ny = rotationX / max），因此位移严格
 * 与倾角成正比，回正时同步归零，不会与 [Motion.TiltReturn] 打架。
 *
 * [depth] 是该层在最大倾角时的位移量；只加在**非交互**的前景文字上——
 * 交互元素（尤其共享元素转场锚点）位移后会让转场起点偏掉，别加。
 */
fun Modifier.cardTiltParallax(
    state: CardTiltState,
    style: CardTiltStyle,
    depth: Dp,
): Modifier = graphicsLayer {
    val maxDegrees = style.maxTiltDegrees.coerceAtLeast(1f)
    val pxPerDegree = depth.toPx() / maxDegrees
    translationX = -state.rotationY.value * pxPerDegree
    translationY = state.rotationX.value * pxPerDegree
}

/**
 * 径向反射流光：跟着触摸点走的三层光，全部用 [BlendMode.Screen] 只提亮。
 *
 * 层叠依据是业界最成熟的"全息卡"实现（simeydotme/pokemon-cards-css 的 `.card__glare`）：
 * 它的高光是 `radial-gradient(farthest-corner circle at <指针>)`，即**半径取指腹到最远角的距离**，
 * 这样无论手指按在哪里，光都刚好铺满卡面而不会露出边界。本实现照此取 [GlareGeometry.broadRadius]。
 *
 * 三层分工（都是同一个 [BlendMode.Screen]，亮度可控地叠加）：
 * 1. 外层柔光：farthest-corner 半径，铺满卡面 → "整张纸被照亮"；
 * 2. 定向扫光：一条过指腹、**垂直于「卡心→指腹」方向**的软光带 → 这才是"流光"。
 *    vanilla-tilt 的高光正是一条被旋转过的定向渐变（`linear-gradient` + `rotate(angle)`，
 *    angle = atan2(指针 - 卡心)），本实现用同一套角度、换成有限宽度的光带而不是半平面洗白；
 *    强度随离中心距离增长——卡心不倾斜就不该有反射，越靠边越斜、反射越亮；
 * 3. 内层亮核：贴着指腹的镜面高光。
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
    val intensity = state.press.value
    if (intensity <= 0.01f) return@drawBehind
    val position = state.touch.value
    if (!position.isSpecified) return@drawBehind

    val glare = glareGeometry(position, size)
    val clip = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
    }
    val peak = style.glareAlpha * intensity

    clipPath(clip) {
        // 0) 背光压暗：让光斑周围"暗下去"一圈，光才浮得起来。
        //    浅色卡面（≈0.98）上 Screen 叠白最多只能 +3 级亮度，肉眼读不出；
        //    业界全息卡（pokemon-cards-css 的 .card__glare）正是靠渐变尾巴的黑色做出落差。
        //    Multiply 只压暗、不染色，卡面底色与文字对比不会被改歪。
        val shade = style.glareShade * intensity
        if (shade > 0.005f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = shade),
                    center = glare.center,
                    radius = glare.broadRadius,
                ),
                radius = glare.broadRadius,
                center = glare.center,
                blendMode = BlendMode.Multiply,
            )
        }

        // 1) 外层柔光：半径刚好到最远角，铺满卡面
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = peak * BroadPeak),
                0.5f to color.copy(alpha = peak * BroadMid),
                1f to Color.Transparent,
                center = glare.center,
                radius = glare.broadRadius,
            ),
            radius = glare.broadRadius,
            center = glare.center,
            blendMode = BlendMode.Screen,
        )

        // 2) 定向扫光：垂直于「卡心→指腹」，强度随离中心的距离增长
        val sheen = peak * SheenPeak * glare.sheenStrength
        if (sheen > 0.01f) {
            drawRect(
                brush = Brush.linearGradient(
                    SheenOuter to Color.Transparent,
                    SheenInner to Color.Transparent,
                    SheenCenter to color.copy(alpha = sheen),
                    SheenInnerMirror to Color.Transparent,
                    SheenOuterMirror to Color.Transparent,
                    start = glare.sheenStart,
                    end = glare.sheenEnd,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.Screen,
            )
        }

        // 3) 内层亮核：贴着指腹的镜面高光
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = peak * CorePeak),
                0.25f to color.copy(alpha = peak * CoreMid),
                0.6f to color.copy(alpha = peak * CoreTail),
                1f to Color.Transparent,
                center = glare.center,
                radius = glare.coreRadius,
            ),
            radius = glare.coreRadius,
            center = glare.center,
            blendMode = BlendMode.Screen,
        )
    }
}

// 三层光的峰值系数：卡心处只有柔光 + 亮核（合计 0.94·peak，与单层实现同亮度），
// 越靠边扫光越强，最亮处约 1.4·peak——倾斜越狠反射越亮，是物理上也讲得通的走向。
private const val BroadPeak = 0.26f
private const val BroadMid = 0.11f
private const val SheenPeak = 0.50f
private const val CorePeak = 0.68f
private const val CoreMid = 0.44f
private const val CoreTail = 0.16f

// 光带剖面：0.44~0.56 是亮区，两侧各留一段透明缓冲，得到一条边界柔和的"扫光"。
private const val SheenOuter = 0f
private const val SheenInner = 0.44f
private const val SheenCenter = 0.50f
private const val SheenInnerMirror = 0.56f
private const val SheenOuterMirror = 1f

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

/**
 * 与 vanilla-tilt 同向（该库 `tiltX = max - x·max·2`、`tiltY = y·max·2 - max`，
 * 再以 `rotateX(tiltY) rotateY(tiltX)` 施加）：**指腹压住的那一角朝观看者抬起来**。
 * 右侧下压时 rotateY 为负，下侧下压时 rotateX 为正。
 */
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

/**
 * 反光的几何：全部由纯函数给出，便于单测钉住（见 `CardTiltTest`）。
 *
 * [sheenStrength] 是 0..1 的"倾斜程度"——指腹到卡心的距离除以半对角线，
 * 卡正中心为 0（不倾斜则没有定向反射），四角为 1。
 */
internal data class GlareGeometry(
    val center: Offset,
    val broadRadius: Float,
    val coreRadius: Float,
    val sheenStart: Offset,
    val sheenEnd: Offset,
    val sheenStrength: Float,
)

/**
 * 把指腹位置换算成三层光的几何。
 *
 * 半径用 `farthest-corner`（指腹到四个角里最远的那个）而不是卡面尺寸的固定倍率：
 * 前者在任何落点都刚好盖满卡面，后者在贴边时会露出渐变的硬边。
 */
internal fun glareGeometry(position: Offset, size: Size): GlareGeometry {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) {
        return GlareGeometry(Offset.Zero, 1f, 1f, Offset.Zero, Offset.Zero, 0f)
    }
    val center = Offset(
        x = position.x.coerceIn(0f, width),
        y = position.y.coerceIn(0f, height),
    )
    val corners = listOf(
        Offset.Zero,
        Offset(width, 0f),
        Offset(0f, height),
        Offset(width, height),
    )
    val broadRadius = corners
        .maxOf { (center - it).getDistance() }
        .coerceAtLeast(1f)

    val longSide = max(width, height)
    val shortSide = min(width, height)
    val coreRadius = (shortSide * 0.58f).coerceAtLeast(longSide * 0.30f)

    val dx = center.x - width / 2f
    val dy = center.y - height / 2f
    val distance = Offset(dx, dy).getDistance()
    // 扫光强度直接复用倾角那一套归一化：指腹压到边上时卡片正好倾到最大角，反射也最亮；
    // 卡心倾角为 0，就不该有定向反射。这样"看起来有多斜"和"反光有多强"永远同一把尺子。
    val normalized = tiltNormalized(center, size)
    val sheenStrength = max(abs(normalized.x), abs(normalized.y))
    val direction = if (distance < 1e-3f) Offset(1f, 0f) else Offset(dx / distance, dy / distance)
    // 光带总长取 2 × 1.25 × 最长边：光带过卡心沿任意方向都能横穿整张卡
    val reach = longSide * 1.25f

    return GlareGeometry(
        center = center,
        broadRadius = broadRadius,
        coreRadius = coreRadius,
        sheenStart = center - direction * reach,
        sheenEnd = center + direction * reach,
        sheenStrength = sheenStrength,
    )
}

private fun tiltFor(position: Offset, size: IntSize, maxDegrees: Float): TiltAngles {
    val normalized = tiltNormalized(
        position = position,
        size = Size(size.width.toFloat(), size.height.toFloat()),
    )
    return tiltAngles(normalized, maxDegrees)
}

/**
 * 让两个轴各自跑向目标角度。
 *
 * 一次事件发两个协程（而不是顺序 await 两个 animateTo）——顺序执行会让 X 走完才轮到 Y，
 * 拖动时两个轴就分了先后。`Animatable` 被新的 `animateTo` 打断时会继承当前速度，
 * 所以高频事件下不会退回"一跳一跳"，而是连续地追。
 */
private fun followTilt(
    state: CardTiltState,
    angles: TiltAngles,
    scope: CoroutineScope,
    spec: FiniteAnimationSpec<Float> = Motion.TiltFollow,
) {
    scope.launch { state.rotationX.animateTo(angles.rotationX, spec) }
    scope.launch { state.rotationY.animateTo(angles.rotationY, spec) }
}
