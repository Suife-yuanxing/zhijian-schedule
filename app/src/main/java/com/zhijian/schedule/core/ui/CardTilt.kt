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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
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

@Composable
fun rememberCardTiltState(vararg keys: Any?): CardTiltState = remember(*keys) {
    CardTiltState(
        rotationX = Animatable(0f),
        rotationY = Animatable(0f),
        press = Animatable(0f),
        touch = mutableStateOf(Offset.Unspecified),
    )
}

/**
 * 让卡片跟随触摸位置做 3D 透视倾斜，松手用 [Motion.TiltReturn] 阻尼回正。
 *
 * **手势归属**：卡片是一块"实体纸片"，指腹落在卡面上就是在摆弄它——一旦拖动超过触摸阈值，
 * 本次手势就归卡片所有，位移被吃掉，父级 verticalScroll 不会同时滚起来。
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
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    return this
        .pointerInput(state, style.maxTiltDegrees) {
            awaitEachGesture {
                // Initial 趟先看见按下；不消费，clickable 与滚动都还能拿到这串事件
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.touch.value = down.position
                followTilt(state, tiltFor(down.position, size, style.maxTiltDegrees), scope)
                scope.launch { state.press.animateTo(1f, Motion.PressIn) }

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
                    if (dragging) change.consume()
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
            // 有效相机距离 = 最长边 × perspectiveScale。**相机越近透视越猛**
            // （react-parallax-tilt 文档："perspective ... The lower the more extreme the tilt gets"）。
            // Compose 的单位不是像素，须除以 CameraDistanceUnit 换算，否则会退化成正交投影。
            cameraDistance =
                max(size.width, size.height) * style.perspectiveScale / CameraDistanceUnit
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
 * `cameraDistance` → 有效相机距离（像素）的换算系数，**实测标定值**。
 *
 * Compose 的 `cameraDistance` 单位既不是像素也不是 dp：本机（1080×2376 @480dpi）
 * 用 `.tmp/cal_sweep.sh` 扫了两点——传入 19.2 时实测近边外溢 +56px（等效 P≈1230），
 * 传入 336 时外溢 ≈0（等效 P≈21,500），两点都落在 `P ≈ v × 64` 上。
 *
 * 换设备/换 Compose 版本后若观感变得"很平"或"撕裂"，重跑那个脚本重新标定本值。
 * 之所以要除以它：[perspectiveScale] 想表达的是「相机距离是几倍卡长」，
 * 直接乘进去会被放大 64 倍，效果等于没有透视（历史 bug 即如此）。
 */
private const val CameraDistanceUnit = 64f

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
