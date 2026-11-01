package com.zhijian.schedule.core.ui.island

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard
import com.zhijian.schedule.core.ui.rememberAnimationsDisabled
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** 浮岛提示的默认展示时长：胶囊下方那条进度条从空走到满所用的毫秒数 */
const val ISLAND_HINT_HOLD_MS: Int = 5_000

/**
 * 浮岛提示的语气：决定图标、强调色、进度条颜色，以及要不要震一下。
 *
 * 只有两档，不是三档：这条通道说的事情只有「成了」和「没成」两种结局。
 * 再加一档「提醒」会让三者的差别退化成颜色深浅，而颜色深浅读不出轻重。
 */
enum class IslandHintTone {
    /**
     * 做成了一件事：对勾 + 强调色，安静地来、安静地走。
     *
     * 不震：成功是预期结果，每成功一次都震，触感就失去了信息量
     * （与 [com.zhijian.schedule.core.ui.buttonFeedback] 的同一条规矩）。
     */
    Done,

    /**
     * 没做成：叉号 + 危险色 + 一次短促的震动。
     *
     * 失败必须能被错过也不能被漏掉——用户此刻的注意力在网页上，未必看着顶部。
     * 所以除了颜色，再加两样不依赖注视的通道：**手感的震**与**视觉的抖**。
     * 两者都只持续三分之一秒，不拦路、不需要确认，但足以把眼睛拉过来。
     *
     * ## 为什么不能只靠颜色
     * 「纸本暖白」的强调色本身是陶土橘（#D97757），与失败用的正红 #E5484D
     * 在对比度上只差 1.25:1——两档压在同一个底上，颜色这一层几乎不传递差别。
     * 所以真正分开两档的是**图标（对勾 / 叉号）**、文案、以及失败独有的抖动与震动：
     * 即使把屏幕调成灰度、即使色觉不同，两者依然一眼可分（WCAG 2.1 1.4.1）。
     */
    Failed,
}

/**
 * 滑入 / 滑出的位移比例。
 *
 * 只走三分之一：浮岛提示贴着内容顶部弹出，整条滑进来会读成「又从上面掉下来一层界面」；
 * 三分之一的高度足以让出现有方向、又不至于挡住在它下面正要被点的东西。
 */
private const val SLIDE_FRACTION = 1f / 3f

/** 失败态那一下横向抖动的总时长：够被看见，又不至于让人等它停 */
private const val SHAKE_MS = 340

/**
 * 抖动来回的次数。
 *
 * 2.5 而不是整数：整周期会让波形在收尾时正好停在起点、位移看起来是「弹回去」；
 * 半周期收尾时位移与振幅**同时**归零，读作「自己站住了」。
 */
private const val SHAKE_CYCLES = 2.5f

/** 抖动幅度（dp）；贴着屏幕两侧还有 12dp 外距时不会顶出边界 */
private const val SHAKE_AMPLITUDE_DP = 6f

/** 进度条高度：胶囊里最细的一档信息，不能和文字抢权重 */
private val BarHeight = 2.dp

/** 进度条左右留白：胶囊两端是半圆，条要落在直段上才不会被弧线啃掉两头 */
private val BarInsetX = 20.dp

/**
 * 浮岛提示：浮在内容之上的一枚胶囊，里面一行字，**下方一条进度条**，走完自动收起。
 *
 * ## 为什么需要这个组件
 * 「已填入保存的教务账号」原本是一行贴在网页上方的常驻说明文字。可它说的是
 * **刚刚发生的一件事**：页面一出现，账密就已经填好了。用户扫一眼之后这句话就没有
 * 信息量了，继续挂着只是把版面让给一句旧闻——而这一屏的高度预算全部要还给教务网页
 * （见 JwImportScreen 顶部注释），任何常驻行都在跟学校页面抢地盘。
 *
 * ## 为什么用进度条，而不是到点硬消失
 * 一条会自己消失的提示必须回答「我还有多久」。没有进度条的自动消失读起来像
 * 「它怎么突然没了」，用户会怀疑自己是不是点错了；进度条把这件不确定的事变成
 * 可预期的：走完即走，中途随时可以无视它继续操作。
 *
 * ## 进度条为什么画在胶囊**内部的下方**
 * 浮在网页上的提示会盖住学校页面的一小条。把剩余时间压进胶囊自己的边界里，
 * 而不是在它下面另挂一根线，是为了让「提示」与「它的寿命」读成同一件东西：
 * 游离在胶囊外的进度条会被当成页面的一部分，胶囊内的一根则是它的仪表。
 *
 * ## 两种语气共用一套骨架
 * 成功与失败只在 [tone] 上分叉：图标、颜色、以及失败额外的那一下震动与抖动。
 * 几何、时长、动效曲线、进度条位置全部相同——同一条通道上的两种结果，
 * 换了形状或时长，用户就得重新学一遍「这玩意儿会自己消失吗」。
 *
 * ## 两个参数各自回答一个问题
 * - [visible] 决定在不在；
 * - [resetKey] 决定**什么时候重新计时**。同一个提示被再次触发时（例如用户又登录了一次），
 *   进度条必须从头走，而不是接着上一轮的余量——所以计时效应挂在它上面，
 *   而不是挂在 [visible] 上（后者只要一直是 true，效应就不会重跑）。
 *
 * ## 系统关掉动画时
 * 不演进度、不抖，但**时长照走**：无障碍用户同样需要「它不会一直杵在这里」这个确定性。
 * 震动保留——「移除动画」关的是运动，不是触觉反馈，而失败的手感正是这次要传达的东西。
 *
 * @param text 一行说明；超过两行省略，胶囊高度因此是稳定的
 * @param resetKey 重新计时的令牌；变化即从头计时、重新抖、重新震
 * @param onFinished 进度走完时回调一次，调用方据此把 [visible] 置 false
 * @param tone 语气；失败档会用叉号、危险色，并震一下
 * @param icon 覆盖默认图标；默认按 [tone] 取对勾或叉号
 * @param holdMillis 展示时长，默认 [ISLAND_HINT_HOLD_MS]
 */
@Composable
fun IslandHint(
    visible: Boolean,
    text: String,
    resetKey: Any?,
    onFinished: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
    tone: IslandHintTone = IslandHintTone.Done,
    icon: ImageVector? = null,
    holdMillis: Int = ISLAND_HINT_HOLD_MS,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            slideInVertically(
                animationSpec = tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut),
            ) { height -> (height * SLIDE_FRACTION).toInt() } + fadeIn(tween(durationMillis = Motion.DUR_MEDIUM)),
        // 离场比入场快：快速消失读作「说完了」，慢消失读作「还赖着」
        exit =
            slideOutVertically(tween(durationMillis = Motion.DUR_FAST)) { height ->
                (height * SLIDE_FRACTION).toInt()
            } + fadeOut(tween(durationMillis = Motion.DUR_FAST)),
        modifier = modifier,
    ) {
        IslandHintBody(
            text = text,
            resetKey = resetKey,
            onFinished = onFinished,
            colors = colors,
            tone = tone,
            icon = icon ?: defaultIcon(tone),
            holdMillis = holdMillis,
        )
    }
}

/**
 * 语气对应的默认图标：成了打对勾，没成打叉。
 *
 * 抽成 [internal] 是为了能进本机单测钉住一条不变量：两档**必须**拿到不同的图标。
 * 原因见 [IslandHintTone.Failed] 里那段：颜色这一层在暖色主题上分不开，
 * 图标是那条不依赖色觉的分界线（WCAG 2.1 1.4.1 Use of Color）。
 */
internal fun defaultIcon(tone: IslandHintTone): ImageVector =
    when (tone) {
        IslandHintTone.Done -> Icons.Filled.Check
        IslandHintTone.Failed -> Icons.Filled.Close
    }

/**
 * 胶囊本体与计时。
 *
 * 计时放在 [AnimatedVisibility] 的内容里，不是放在外面：内容随离场动画一起被销毁，
 * 效应跟着取消，于是「消失之后还在倒计时、又把已经消失的提示关一次」不可能发生。
 */
@Composable
private fun IslandHintBody(
    text: String,
    resetKey: Any?,
    onFinished: () -> Unit,
    colors: CourseColors,
    tone: IslandHintTone,
    icon: ImageVector,
    holdMillis: Int,
) {
    // 回调每轮组合都可能是新实例：进度跑完时才取用，直接闭包捕获会拿到首帧那个旧 lambda
    val finish = rememberUpdatedState(onFinished)
    val animationsOff = rememberAnimationsDisabled()
    val fill = remember(resetKey) { Animatable(0f) }
    // 横向抖动单独一条时间线：它只在前三分之一秒有事，和五秒的倒计时没有共同点，
    // 合成一条要处处换算。关动画时不跑，位移恒为 0。
    val shake = remember(resetKey) { Animatable(0f) }
    LaunchedEffect(resetKey, animationsOff) {
        fill.snapTo(0f)
        if (animationsOff) {
            delay(holdMillis.toLong())
        } else {
            // 线性：进度条要读作「还剩多少时间」，任何缓动都会让中段走得比真实时间快或慢
            fill.animateTo(1f, tween(durationMillis = holdMillis, easing = LinearEasing))
        }
        finish.value()
    }
    // 抖动与震动另起一条效应：上面那条要跑满五秒，把抖动挂进去就得在它里面开子协程，
    // 而子协程会被那五秒的异常取消（例如页面重排导致中途重组）一起带走
    val context = LocalContext.current
    LaunchedEffect(resetKey, tone, animationsOff) {
        if (tone != IslandHintTone.Failed) return@LaunchedEffect
        // 用它而不是 HapticFeedbackType.Reject：后者在 ColorOS 上是 480ms 的长震，
        // 与「五秒后自己消失的小提示」完全不是一个量级（真机实测见 IslandHaptics）
        IslandHaptics.failure(context)
        if (animationsOff) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(1f, tween(durationMillis = SHAKE_MS, easing = LinearEasing))
    }
    val accent = if (tone == IslandHintTone.Failed) rememberIslandDanger() else colors.accent
    val amplitudePx = with(LocalDensity.current) { SHAKE_AMPLITUDE_DP.dp.toPx() }
    val shape = RoundedCornerShape(percent = 50)
    Column(
        Modifier
            .fillMaxWidth()
            // 抖动作用在**整颗胶囊**上，不是只抖文字：只抖文字读作「渲染出错」，
            // 整颗抖才读作「这东西自己打了个冷颤」
            .graphicsLayer { translationX = dampedShake(shake.value) * amplitudePx }
            .clip(shape)
            // RAISED：与「记住这次登录？」气泡同一档——两者都是浮在网页之上、等着被看一眼的卡
            .metalCard(shape = shape, color = colors.surface, tone = CardTone.RAISED)
            // 播报给读屏：这是异步发生、随后自动消失的状态，不主动念出来就完全错过了
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(top = TextTopPad, bottom = TextBottomPad),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = TextStartPad, end = BarInsetX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(IconSize),
            )
            Spacer(Modifier.width(IconGap))
            Text(
                text = text,
                color = colors.text,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(TextBarGap))
        IslandHintCountdown(colors = colors, barColor = accent, progress = { fill.value })
    }
}

/**
 * 抖动波形：振幅衰减的正弦，p 在 0..1 上由线性时钟推进，返回值是 -1..1 的比例。
 *
 * ## 为什么是衰减正弦而不是关键帧
 * 关键帧要手写五六个时间点与幅度，调一次得重新算一遍；而「越抖越小、最后停住」
 * 本来就是一条衰减正弦的自然形状。振幅用 (1-p) 而不是指数：线性收尾时位移与振幅
 * **同一帧**归零，不会留下半帧的硬切。
 *
 * ## 为什么从 0 起、在 0 落
 * p=0 时 sin(0)=0，胶囊不会凭空跳一下；p=1 时 sin(5π)=0 且 (1-p)=0，
 * 两个零点重合，收尾干净。
 *
 * 返回值是**比例**而不是 dp：幅度留给调用处按屏幕与点击目标单独调，波形不用动。
 */
internal fun dampedShake(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return sin(p * SHAKE_CYCLES * TWO_PI_F) * (1f - p)
}

/** 2π（Float）：避免每帧把 Double 常量搬进浮点运算 */
private const val TWO_PI_F = (2.0 * PI).toFloat()

/**
 * 胶囊底部那条倒计时进度条。
 *
 * 进度从**绘制阶段**读（[Modifier.drawBehind] 的 lambda 里取 [progress]），不走组合阶段：
 * 读在组合里会让这颗胶囊五秒内每帧重组一次，而它正压在 WebView 之上——
 * 这里要的只是每帧重画一根两 dp 的条，不是每帧重算一次布局。
 *
 * 未走到的部分用 [CourseColors.border]：它是「这条轨道总长多少」的参照，
 * 没有轨道时进度条会读成一个自己随机变长的色块。
 */
@Composable
private fun IslandHintCountdown(
    colors: CourseColors,
    barColor: Color,
    progress: () -> Float,
) {
    val barShape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BarInsetX)
            .height(BarHeight)
            .clip(barShape)
            .background(colors.border)
            .drawBehind {
                val w = size.width * progress().coerceIn(0f, 1f)
                if (w > 0f) drawRect(color = barColor, size = Size(width = w, height = size.height))
            },
    )
}

/** 文字行上沿留白 */
private val TextTopPad = 11.dp

/** 文字行下沿到进度条的留白 */
private val TextBottomPad = 8.dp

/** 文字行左起留白：与胶囊半圆端对齐后仍留一点呼吸 */
private val TextStartPad = 18.dp

/** 图标与文字之间 */
private val IconGap = 7.dp

/** 前置图标尺寸：与正文同高，不喧宾夺主 */
private val IconSize = 13.dp

/** 文字与进度条之间 */
private val TextBarGap = 8.dp
