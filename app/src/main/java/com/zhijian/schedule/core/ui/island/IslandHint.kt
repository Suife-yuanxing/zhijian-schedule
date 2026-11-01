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
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 浮岛提示的默认展示时长：胶囊下方那条进度条从空走到满所用的毫秒数 */
const val ISLAND_HINT_HOLD_MS: Int = 5_000

/**
 * 滑入 / 滑出的位移比例。
 *
 * 只走三分之一：浮岛提示贴着网页顶部弹出，整条滑进来会读成「又从上面掉下来一层界面」；
 * 三分之一的高度足以让出现有方向、又不至于挡住在它下面正要被点的东西。
 */
private const val SLIDE_FRACTION = 1f / 3f

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
 * ## 两个参数各自回答一个问题
 * - [visible] 决定在不在；
 * - [resetKey] 决定**什么时候重新计时**。同一个提示被再次触发时（例如用户又登了一次、
 *   我们又填了一次），进度条必须从头走，而不是接着上一轮的余量——所以计时效应挂在它上面，
 *   而不是挂在 [visible] 上（后者只要一直是 true，效应就不会重跑）。
 *
 * ## 系统关掉动画时
 * 不演进度（进度条停在空位当作装饰下划线），但**时长照走**：无障碍用户同样需要
 * 「它不会一直杵在这里」这个确定性，只是不需要那段运动。
 *
 * @param text 一行说明；超过两行省略，胶囊高度因此是稳定的
 * @param resetKey 重新计时的令牌；变化即从头计时
 * @param onFinished 进度走完时回调一次，调用方据此把 [visible] 置 false
 * @param icon 前置图标，默认对勾（这个组件的语义就是「刚才做成了一件事」）
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
    icon: ImageVector = Icons.Filled.Check,
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
            icon = icon,
            holdMillis = holdMillis,
        )
    }
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
    icon: ImageVector,
    holdMillis: Int,
) {
    // 回调每轮组合都可能是新实例：进度跑完时才取用，直接闭包捕获会拿到首帧那个旧 lambda
    val finish = rememberUpdatedState(onFinished)
    val animationsOff = rememberAnimationsDisabled()
    val fill = remember(resetKey) { Animatable(0f) }
    LaunchedEffect(resetKey, animationsOff) {
        if (animationsOff) {
            delay(holdMillis.toLong())
            finish.value()
            return@LaunchedEffect
        }
        fill.snapTo(0f)
        // 线性：进度条要读作「还剩多少时间」，任何缓动都会让中段走得比真实时间快或慢
        fill.animateTo(1f, tween(durationMillis = holdMillis, easing = LinearEasing))
        finish.value()
    }
    val shape = RoundedCornerShape(percent = 50)
    Column(
        Modifier
            .fillMaxWidth()
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
                tint = colors.accent,
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
        IslandHintCountdown(colors = colors, progress = { fill.value })
    }
}

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
    progress: () -> Float,
) {
    val barShape = RoundedCornerShape(percent = 50)
    val barColor = colors.accent
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
