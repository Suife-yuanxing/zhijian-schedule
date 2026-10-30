package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.ui.island.Contrast

/*
 * 涟漪加载态与渐变勾选标记：两个源自 uiverse.io 的微交互，按项目既有动效语言重写。
 *
 * 为什么单独成文件而不是塞进 MotionModifiers.kt：这两个是**组件**（有 @Composable 与尺寸），
 * 不是修饰符。项目里 modifiers 与 components 分得很清（见 MotionModifiers.kt 只放 Modifier 扩展）。
 */

/** 涟漪的几何基准：全部以 min(width, height) 为 1.0 的相对值表达，与屏宽无关。 */
private object RippleSpec {
    // 圆环起始半径占可用半径的比例：不从一个点冒出来，避免「凭空出现」的突兀感
    const val START_RADIUS_RATIO = 0.28f

    // 起始半径之外还要走完的行程比例
    const val TRAVEL_RATIO = 0.72f

    // 刚出发时的最大不透明度；留 0.15 的余量是为了让最外层略淡，层次更明显
    const val MAX_ALPHA = 0.85f

    // 低于此透明度就不再绘制，省掉不可见的 draw 调用
    const val MIN_VISIBLE_ALPHA = 0.01f

    // 静态退路里每往外一层的不透明度衰减量
    const val STATIC_ALPHA_STEP = 0.09f

    // 静态退路里最外层的基准不透明度
    const val STATIC_ALPHA_START = 0.45f

    // 静态退路里每往外一层的半径收缩比例
    const val STATIC_RADIUS_STEP = 0.09f
}

/** 勾选标记的几何基准：以 24dp 画布为基准的相对比例。 */
private object CheckMarkSpec {
    // 画布边长
    val SIZE = 24.dp

    // 未选中底色（白）的不透明度
    const val IDLE_BG_ALPHA = 0.24f

    // 选中共色渐变的不透明度上限；原作是 0.9
    const val CHECKED_BG_ALPHA = 0.9f

    // 渐变终点向量的水平分量，对应 CSS 144deg
    const val GRADIENT_END_X = 0.809f

    // 渐变终点向量的垂直分量，对应 CSS 144deg
    const val GRADIENT_END_Y = 0.588f

    // 对勾起笔前保留的过渡比例：底色先亮起，勾随后画
    const val TICK_DELAY_RATIO = 0.15f

    // reduce-motion 下判定「算选中」的阈值：过半点即吸附到 1，否则吸附到 0
    const val REDUCED_MOTION_THRESHOLD = 0.5f

    // 起笔点
    const val P0_X = 0.30f
    const val P0_Y = 0.52f

    // 转折点
    const val P1_X = 0.45f
    const val P1_Y = 0.68f

    // 收笔点
    const val P2_X = 0.72f
    const val P2_Y = 0.36f

    // 勾的线宽占画布边长的比例
    const val TICK_STROKE_RATIO = 0.10f

    // 未选中态描边的线宽占画布边长的比例：比勾细一档，读作「收边」而不是第二个字形
    const val IDLE_RING_RATIO = 0.055f

    // 原作三色渐变的起始色（紫）
    val GRADIENT_START = Color(0xFFAF40FF)

    // 原作三色渐变的中间色（蓝）
    val GRADIENT_MIDDLE = Color(0xFF5B42F3)

    // 原作三色渐变的结束色（青）
    val GRADIENT_END = Color(0xFF00DDEB)

    // 原作默认渐变，供两个函数共享同一份定义，避免两处字面量各写一遍
    val DEFAULT_GRADIENT = listOf(GRADIENT_START, GRADIENT_MIDDLE, GRADIENT_END)
}

/**
 * 同心圆涟漪加载态（手法移植自 uiverse.io/Smit-Prajapati/tricky-turtle-16）。
 *
 * 原作的规则只有两条：每层延迟一个固定相位、所有层共用同一周期与同一条缓动。
 * 参数对照：原作 --duration 2s，此处 [periodMillis] 默认 2000；原作每层 animation-delay 0.2s，
 * 即 0.2 / 2 = 0.1 个周期的相位偏移，见 [phaseStep]。
 *
 * 为什么用 Canvas 一次画完而不是叠 N 个 Composable：每层若各自持有动画状态，一帧内会有 N 次重组
 * 加 N 次布局；这里只有 1 个进度值在变，其余全是 draw 阶段的算术，符合 deferring-state-reads 的
 * 「把高频状态压到 Draw 层」。
 *
 * reduce-motion 护栏是硬要求：无限循环的装饰性动效必须服从系统开关（WCAG 2.3.3 与项目 MotionPrefs
 * 的约定）。关闭时退化为一只静止的同心圆环，仍承担「正在处理」的语义，而不是直接消失。
 *
 * @param modifier 影响尺寸；半径按 min(width, height) 推导，任意方形或圆形区域都能用
 * @param color 圆环颜色；透明度由相位实时折算，调用处不必再乘 alpha
 * @param rings 层数；原作 4 层，再多在小尺寸下会糊成一片
 * @param periodMillis 一整圈的周期，原作 2s
 * @param phaseStep 相邻层的相位差（0 到 1）；0.1 即原作的 0.2s / 2s
 * @param strokeWidth 描边粗细；原作 border-top 1px，此处默认 1.5dp 以便在高分屏上可见
 */
@Composable
fun RippleLoader(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray,
    rings: Int = 4,
    periodMillis: Int = 2000,
    phaseStep: Float = 0.1f,
    strokeWidth: Dp = 1.5.dp,
) {
    val animationsDisabled = rememberAnimationsDisabled()
    if (animationsDisabled) {
        // 静态退路：由内到外逐层变淡的同心圆环，读作「加载中但不动」
        Canvas(modifier) {
            val minSide = size.minDimension
            val stroke = strokeWidth.toPx()
            repeat(rings) { i ->
                val radius = minSide * (0.5f - RippleSpec.STATIC_RADIUS_STEP * i)
                val alpha = RippleSpec.STATIC_ALPHA_START - RippleSpec.STATIC_ALPHA_STEP * i
                if (radius > 0f && alpha > 0f) {
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = radius,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        return
    }

    // 单一驱动源：所有层共享这一个进度，才读成同一片水波，而不是 N 个各跳各的动画
    val progress by rippleProgress(periodMillis)

    Canvas(modifier) {
        val maxRadius = size.minDimension / 2f
        val stroke = strokeWidth.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(rings) { i ->
            // 相位偏移：第 i 层比第 0 层晚 phaseStep 乘 i 个周期出发
            val phase = (progress - phaseStep * i).mod(1f)
            val radius = maxRadius * (RippleSpec.START_RADIUS_RATIO + RippleSpec.TRAVEL_RATIO * phase)
            val alpha = (1f - phase) * RippleSpec.MAX_ALPHA
            if (alpha > RippleSpec.MIN_VISIBLE_ALPHA) {
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * 0 到 1 无限循环的浮点进度。
 *
 * 单独抽出来是为了让「谁在驱动」一目了然：涟漪的各层只做算术，不各自持有动画。
 * 缓动取 [Motion.EaseOut]（与项目既有语言一致）；原作是 ease-in-out，这里用 EaseOut 是因为
 * 扩散类动效「起步快、末端缓」更贴近水波，也与 [Motion.EaseRevealDecelerate] 的方向一致。
 */
@Composable
private fun rippleProgress(periodMillis: Int): State<Float> {
    val transition = rememberInfiniteTransition(label = "rippleLoader")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = periodMillis, easing = Motion.EaseOut),
                repeatMode = RepeatMode.Restart,
            ),
        label = "rippleProgress",
    )
}

/**
 * 三色渐变勾选标记（手法移植自 uiverse.io/MikeAndrewDesigner/jolly-gecko-33）。
 *
 * 原作的关键不是渐变本身，而是三件事同步：底色从半透明切到渐变、整体不透明度 0.4 到 0.9、
 * 对勾从无到有，全部压在 transition: all 0.2s ease 一条时间线上。这里保留 200ms 与 EaseOut，
 * 只把原作的三色交给调用处，因为项目有多套主题预设，写死紫青会和 liquid-glass 基调打架。
 *
 * 为什么勾用 Canvas 画而不是叠 Icon：对勾要随 [fill] 做画线进度（落笔到收笔），Icon 只能整枚缩放，
 * 读起来是「突然出现」而不是「勾上去」。
 *
 * @param fill 0 表示完全未选，1 表示完全选中；中间值由调用处的动画提供，这样勾选、取消以及
 *             状态从外部变化（如全选）都能复用同一条过渡
 * @param gradientColors 选中态的三色渐变，默认取原作配色；生产页面应改传 [accentGradient] 以跟随主题
 * @param strokeColor 对勾颜色；渐变底色之上用白勾，浅色渐变则传深色
 * @param markSize 画布边长；内部所有几何都按边长的比例推导，任意尺寸都成立
 * @param idleRingColor 未选中态的外圈描边；默认透明。**浅色底上必须传**，否则
 *   白色 24% 的半透明底与纸色底几乎同色，未选中的标记等于不存在（真机实测确实看不见）。
 */
@Composable
fun GradientCheckMark(
    fill: Float,
    modifier: Modifier = Modifier,
    gradientColors: List<Color> = CheckMarkSpec.DEFAULT_GRADIENT,
    strokeColor: Color = gradientStrokeColor(gradientColors),
    markSize: Dp = CheckMarkSpec.SIZE,
    idleRingColor: Color = Color.Transparent,
) {
    val animationsDisabled = rememberAnimationsDisabled()
    // reduce-motion 下直接吸附到终态，不做过渡
    val f =
        if (animationsDisabled) {
            if (fill >= CheckMarkSpec.REDUCED_MOTION_THRESHOLD) 1f else 0f
        } else {
            fill.coerceIn(0f, 1f)
        }

    Box(modifier) {
        Canvas(Modifier.size(markSize)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // 底：未选中是半透明实体，选中是渐变，两者按 f 交叉淡出
            val brush =
                Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset.Zero,
                    // 终点按 CSS 144deg 折算的对角向量，与原作同向
                    end = Offset(size.width * CheckMarkSpec.GRADIENT_END_X, size.height * CheckMarkSpec.GRADIENT_END_Y),
                )
            drawCircle(
                color = Color.White.copy(alpha = CheckMarkSpec.IDLE_BG_ALPHA * (1f - f)),
                radius = radius,
                center = center,
            )
            // 未选中态的描边：白色半透明底在浅色界面上与底色几乎同色（真机实测整枚看不见），
            // 一条同色系的细边才能把「这里有一个可勾的标记」讲清楚。
            // 淡出与填充同步反向：选中之后渐变接管圆面，描边就没有存在的意义了。
            if (idleRingColor.alpha > 0f && f < 1f) {
                drawCircle(
                    color = idleRingColor.copy(alpha = idleRingColor.alpha * (1f - f)),
                    radius = radius,
                    center = center,
                    style = Stroke(width = size.minDimension * CheckMarkSpec.IDLE_RING_RATIO),
                )
            }
            if (f > RippleSpec.MIN_VISIBLE_ALPHA) {
                drawCircle(
                    brush = brush,
                    alpha = f * CheckMarkSpec.CHECKED_BG_ALPHA,
                    radius = radius,
                    center = center,
                )
            }

            // 勾：按画线进度绘制，起笔、转折、收笔三点见 CheckMarkSpec
            if (f > CheckMarkSpec.TICK_DELAY_RATIO) {
                val t = ((f - CheckMarkSpec.TICK_DELAY_RATIO) / (1f - CheckMarkSpec.TICK_DELAY_RATIO)).coerceIn(0f, 1f)
                val p0 = Offset(size.width * CheckMarkSpec.P0_X, size.height * CheckMarkSpec.P0_Y)
                val p1 = Offset(size.width * CheckMarkSpec.P1_X, size.height * CheckMarkSpec.P1_Y)
                val p2 = Offset(size.width * CheckMarkSpec.P2_X, size.height * CheckMarkSpec.P2_Y)
                val firstLen = (p1 - p0).getDistance()
                val secondLen = (p2 - p1).getDistance()
                val drawn = (firstLen + secondLen) * t
                val path = Path()
                path.moveTo(p0.x, p0.y)
                if (drawn <= firstLen) {
                    val ratio = if (firstLen == 0f) 0f else drawn / firstLen
                    path.lineTo(p0.x + (p1.x - p0.x) * ratio, p0.y + (p1.y - p0.y) * ratio)
                } else {
                    path.lineTo(p1.x, p1.y)
                    val ratio = if (secondLen == 0f) 0f else ((drawn - firstLen) / secondLen).coerceIn(0f, 1f)
                    path.lineTo(p1.x + (p2.x - p1.x) * ratio, p1.y + (p2.y - p1.y) * ratio)
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style =
                        Stroke(
                            width = size.minDimension * CheckMarkSpec.TICK_STROKE_RATIO,
                            cap = StrokeCap.Round,
                        ),
                )
            }
        }
    }
}

/**
 * [GradientCheckMark] 的动画包装：把布尔状态折成 0 到 1 的连续值。
 *
 * 拆成两个函数而不是合并：fill 版本可以让调用处在多选场景里做交错（每一项延迟 20ms），
 * 而布尔版本只负责单点状态。原作的 0.2s 对应 Motion.DUR_FAST 加 50ms，
 * 落在项目既有 150 与 260 两档之间，不引入新的时长档位。
 */
@Composable
fun AnimatedGradientCheckMark(
    checked: Boolean,
    modifier: Modifier = Modifier,
    gradientColors: List<Color> = CheckMarkSpec.DEFAULT_GRADIENT,
    strokeColor: Color = gradientStrokeColor(gradientColors),
    markSize: Dp = CheckMarkSpec.SIZE,
    idleRingColor: Color = Color.Transparent,
) {
    val fill by
        animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = tween(durationMillis = Motion.DUR_FAST + 50, easing = Motion.EaseOut),
            label = "gradientCheckFill",
        )
    GradientCheckMark(
        fill = fill,
        modifier = modifier,
        gradientColors = gradientColors,
        strokeColor = strokeColor,
        markSize = markSize,
        idleRingColor = idleRingColor,
    )
}

/**
 * 由主题色派生一条三停渐变，替代原作的紫 → 蓝 → 青。
 *
 * 为什么必须派生而不是照抄：项目有八套预设主题（纸本 / 清爽极简 / 多彩 / 墨夜 / 液态玻璃 /
 * 动态取色 …），写死紫青会在暖白纸本上跳出一块完全不属于该主题的冷色，读起来像「贴上去的贴纸」。
 * 派生规则只做两件事——起点压深一点、终点提亮一点，中间保留强调色本身，
 * 于是渐变的方向始终是「这门课自己的颜色在受光」，与 [com.zhijian.schedule.core.theme.CourseTone]
 * 的高光/暗面同一思路。
 *
 * 任何单色都能喂进来：成功态的绿、强调色的橙红、课程身份色都成立。
 */
fun accentGradient(accent: Color): List<Color> {
    var darken = GRADIENT_DARKEN
    var lighten = GRADIENT_LIGHTEN
    repeat(GRADIENT_ADAPT_STEPS) {
        val candidate = gradientOf(accent, darken, lighten)
        if (bestStrokeContrast(candidate) >= MIN_GRAPHIC_CONTRAST) return candidate
        // 渐变是装饰，勾是信号：勾读不清时该收的是渐变，而不是硬塞一个低对比的勾。
        // 逐步把两端往强调色本身收，收到「中间调」为止 —— 单色强调色对白勾几乎总有 4:1 以上。
        darken *= GRADIENT_ADAPT_SHRINK
        lighten *= GRADIENT_ADAPT_SHRINK
    }
    return listOf(accent, accent, accent)
}

private fun gradientOf(
    accent: Color,
    darken: Float,
    lighten: Float,
): List<Color> =
    listOf(
        lerp(accent, Color.Black, darken),
        accent,
        lerp(accent, Color.White, lighten),
    )

/** 这组色停上「最好的那个勾色」能拿到的对比度；[accentGradient] 用它判断要不要收窄色阶 */
private fun bestStrokeContrast(colors: List<Color>): Double {
    val whiteWorst = colors.minOf { Contrast.ratio(Color.White, it) }
    val darkWorst = colors.minOf { Contrast.ratio(DARK_STROKE, it) }
    return maxOf(whiteWorst, darkWorst)
}

/**
 * 该用白勾还是深勾：按渐变里**最不利的那一停**反解，保证勾与底至少 3:1（WCAG 2.1 图形门槛）。
 *
 * 为什么不能一律用白勾：浅色主题的强调色本就偏亮（纸本 #D97757、成功绿 #2BB673），
 * 提亮终点之后渐变右半接近白，白勾压上去实测只有 2.24:1——远看就是「一枚空圆」。
 * 反过来深色主题的强调色偏深，一律用深勾又会糊成一块。所以按亮度算，两头都对。
 *
 * 为什么必须取「最差落点」而不是平均亮度：勾是从左下穿到右上的长笔画，它同时压在三停上，
 * 任何一停读不清，整枚勾就断成两截。第一版写的是平均亮度，纸本的浅端因此被平均掉，
 * 单测现场就红了（2.24 < 3.0）。这里对每个候选色遍历全部色停，取各自的最小比值再比较。
 */
fun gradientStrokeColor(colors: List<Color>): Color {
    if (colors.isEmpty()) return Color.White
    val whiteWorst = colors.minOf { Contrast.ratio(Color.White, it) }
    val darkWorst = colors.minOf { Contrast.ratio(DARK_STROKE, it) }
    // 两个都不达标时选更不坏的那个：宁可对比偏低，也不要出现「深底深勾」这种整体不可见
    return if (whiteWorst >= darkWorst) Color.White else DARK_STROKE
}

// 起点压深比例：太重会把颜色压成脏深色，0.18 时仍看得出原色相
private const val GRADIENT_DARKEN = 0.18f

// 终点提亮比例：0.32 之后浅色主题下会趋近纯白，渐变层次消失
private const val GRADIENT_LIGHTEN = 0.26f

// 收窄色阶的尝试次数：3 次就把 0.26 收到 0.056，足够覆盖全部八套主题
private const val GRADIENT_ADAPT_STEPS = 3

// 每次收窄的倍率：太猛会让渐变一下子变得看不见，0.6 是「仍看得出层次」的下限
private const val GRADIENT_ADAPT_SHRINK = 0.6f

// WCAG 2.1 对图形与界面组件的最低对比度
private const val MIN_GRAPHIC_CONTRAST = 3.0

// 白勾不够时改用的深墨：比纯黑柔，压在彩底上不发脏
private val DARK_STROKE = Color(0xFF1B1B1F)
