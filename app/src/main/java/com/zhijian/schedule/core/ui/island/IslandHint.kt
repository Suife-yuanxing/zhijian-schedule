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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.OverlayMotion
import com.zhijian.schedule.core.ui.TypeScale
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard
import com.zhijian.schedule.core.ui.overlayShadow
import com.zhijian.schedule.core.ui.rememberAnimationsDisabled
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** 浮岛提示的默认展示时长：胶囊下方那条进度条从空走到满所用的毫秒数 */
const val ISLAND_HINT_HOLD_MS: Int = 5_000

/**
 * 浮岛提示的语气：决定图标、强调色、进度条颜色，以及要不要震一下。
 *
 * ## 为什么是三档
 * 这条通道原本只有「成了」和「没成」两档，理由是「再加一档会让三者的差别退化成
 * 颜色深浅」。那条理由仍然成立——所以第三档 [Pending] **不带新颜色**：它与 [Done]
 * 共用同一支强调色，靠图标（问号状的 i / 对勾）分开。
 *
 * 但它确实必须存在。2026-11 起这条通道要送一条**待办**：「这个学期的开学日期还没有
 * 来源，去设置页填一次」。它既不是成功（没做成任何事），也不是失败（不是用户的错，
 * 也不该每次进设置页都红叉加震动训他一遍）——硬塞进 [Failed] 会让一条只需要读一次的
 * 说明变成每次都被惊动的告警，塞进 [Done] 则会给它打上一个对勾。
 *
 * 三档共用同一套几何、时长与进度条：同一条通道上的三种结局，换了形状用户就得
 * 重新学一遍「这玩意儿会自己消失吗」。
 */
enum class IslandHintTone {
    /**
     * 做成了一件事：对勾 + 强调色，安静地来、安静地走。
     *
     * 不震：成功是预期结果，每成功一次都震，触感就失去了信息量
     * （与 `buttonFeedback` 的同一条规矩）。
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

    /**
     * 待办：有事等用户去做，但**现在没坏**。
     *
     * 与 [Done] 同一支强调色、同一个 i 字图标，不震也不抖——它说的是「顺路去做一下」，
     * 不是「出事了」。真正把三档分开的仍然是**图标**（对勾 / 叉号 / 信息）
     * 与文案，颜色这一层只负责让它们都够醒目（WCAG 2.1 1.4.1）。
     */
    Pending,
}

/** 失败态那一下横向抖动的总时长：够被看见，又不至于让人等它停 */
private const val SHAKE_MS = 340

/**
 * 抖动**等落地**之后再开始。
 *
 * ## 为什么不能和入场同时起
 * 上一版把抖动挂在出现的那一帧：胶囊一边从上方滑进来、一边左右晃。
 * 两段位移叠在同一个时间窗里，读到的不是「它打了个冷颤」，而是
 * 「这条提示渲染得在抖」——像掉帧，像画错了。真机上就是这么读的。
 *
 * 取 [Motion.DUR_MEDIUM] 而不是写死一个数：入场动画用的就是它，
 * 两处取值一旦分家，改了入场时长而忘了这里，抖动就会重新压在滑入的半路上。
 */
private const val SHAKE_DELAY_MS = Motion.DUR_MEDIUM

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
        // 进出场走 [OverlayMotion]：与本页另外两块浮层（WAF 横幅、保存气泡）同一套时长与曲线。
        // 上一版离场用的是 tween 的默认曲线（FastOutSlowIn），同一条提示进来一个手感、
        // 出去另一个手感；而旁边那条横幅的离场时长还比入场长——两块浮层先后出现时，
        // 这个差别是看得见的。
        // 入场：从**上方**滑下来。它贴在网页顶部，从上面来与它的落点同向，
        // 读作「从屏幕外落进来」；从下往上顶会穿过正在读的网页内容。
        enter =
            slideInVertically(animationSpec = OverlayMotion.enter()) { height ->
                OverlayMotion.slideUp(height)
            } + fadeIn(OverlayMotion.enter()),
        // 离场：向**上方**离开——从哪个方向来、就从哪个方向走。
        //
        // 改造前它往下走（正偏移）。那一步与入场方向相反，读到的不是"说完了"，
        // 而是「被吸进网页里去了」：它会穿过下面那层网页内容，而那颗胶囊恰恰是
        // 我们盖在别人页面上的东西，往页面里沉成了一件矛盾的事。
        // 向上离开则干净——和入场同一条轴、同一个起落点，来回是一对镜像。
        //
        // 时长比入场短一截：快速消失读作「说完了」，慢消失读作「还赖着」。
        exit =
            slideOutVertically(animationSpec = OverlayMotion.exit()) { height ->
                OverlayMotion.slideUp(height)
            } + fadeOut(OverlayMotion.exit()),
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
 *
 * 两个都取 `Icons.Filled`：素材库只有 core（未引入 extended），也确实不需要更多——
 * 图标在 [IconBadge] 里被挖空成一枚面色字形，字形细节在 11dp 上本来也读不出来。
 */
internal fun defaultIcon(tone: IslandHintTone): ImageVector =
    when (tone) {
        IslandHintTone.Done -> Icons.Filled.Check
        IslandHintTone.Failed -> Icons.Filled.Close
        // 信息而不是 Warning：这一档说的不是「出错了」，是一个还没做过的事项。
        // 三角感叹号会把它读成告警，而它每次进设置页都可能再出现一次。
        IslandHintTone.Pending -> Icons.Filled.Info
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
        // 等它落位再抖：理由见 SHAKE_DELAY_MS。关动画时没有入场，不需要等。
        if (!animationsOff) delay(SHAKE_DELAY_MS.toLong())
        // 用它而不是 HapticFeedbackType.Reject：后者在 ColorOS 上是 480ms 的长震，
        // 与「五秒后自己消失的小提示」完全不是一个量级（真机实测见 IslandHaptics）
        IslandHaptics.failure(context)
        if (animationsOff) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(1f, tween(durationMillis = SHAKE_MS, easing = LinearEasing))
    }
    // 语气色与轨道色来自**同一个求解器**（IslandTokens），两档语气与两段进度条
    // 因此压在同一个可见度基准上：不会出现"成功偏淡、失败偏艳"或"某套预设没有轨道"
    val accent = rememberIslandTone(tone)
    val track = rememberIslandTrack()
    // 挖空圆徽的底色：图标要用"胶囊自己的面"来画，才读作被挖空。
    // 金属档的面是着色器算出来的，不是 colors.surface——与求解器取同一个答案
    val pillFace = rememberIslandFace()
    val amplitudePx = with(LocalDensity.current) { SHAKE_AMPLITUDE_DP.dp.toPx() }
    val shape = RoundedCornerShape(percent = 50)
    Column(
        Modifier
            .fillMaxWidth()
            // 抖动作用在**整颗胶囊**上，不是只抖文字：只抖文字读作「渲染出错」，
            // 整颗抖才读作「这东西自己打了个冷颤」
            .graphicsLayer { translationX = dampedShake(shake.value) * amplitudePx }
            // 投影：这颗胶囊压在**别人的网页**上。没有影时它读作网页自带的一块横幅
            // （网页自己也有白底卡片），有一层软影才读作"我们盖上去的"。
            // 配方与同屏的 WAF 横幅、保存气泡共用（OverlayMotion），三块浮层同一层高度。
            .overlayShadow(shape)
            .clip(shape)
            // RAISED：与「记住这次登录？」气泡同一档——两者都是浮在网页之上、等着被看一眼的卡
            .metalCard(shape = shape, color = colors.surface, tone = CardTone.RAISED)
            .pillEdge(shape, colors)
            // 播报给读屏：这是异步发生、随后自动消失的状态，不主动念出来就完全错过了
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(top = PillTopPad, bottom = PillBottomPad),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = TextStartPad, end = TextStartPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = icon, tint = accent, face = pillFace)
            Spacer(Modifier.width(IconGap))
            Text(
                text = text,
                // 字号与行高来自全站标尺（TypeScale），不在这里单独取值：
                // 改造前这里是 12.5sp / 17sp，而同一屏的说明文字是 11.5sp、
                // 气泡标题是 13.5sp——半像素字号是"每处差一点点、各调半磅"的产物
                style = TypeScale.LabelStrong,
                color = colors.text,
                // 中文避头尾：词组内不断行，标点不落在行首。
                // 不设这一条时真机上「（202530002131）」会在「（」后面整个折下去，
                // 而后半句被 ellipsis 吃掉——用户读到的是一条断成两截的话。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(TextBarGap))
        IslandHintCountdown(barColor = accent, trackColor = track, progress = { fill.value })
    }
}

/**
 * 胶囊的 hairline 边：与底部导航浮岛**同一条边缘契约**（`FloatingIslandBar` 里的
 * `clip + background + border`）。
 *
 * ## 为什么投影不够，还要一条边
 * 投影只在"底下比它亮"时成立。这颗胶囊压的是**别人的网页**——深色网页、
 * 或者用户在系统里开了强制深色时，黑投影落在黑底上等于没有，胶囊就成了一张
 * 平贴在页面上的剪影。墨夜纯黑主题那一档最极端：胶囊面 #0A0A0B、页面纯黑，
 * 没有任何东西说明它的边界在哪。
 *
 * 一条 hairline 不依赖底色明暗，任何情况下都定义得出轮廓——这正是底部浮岛
 * 当初加描边的同一条理由，两处取同一个答案，形状语言才是一套。
 *
 * ## 为什么金属档不画
 * 金属面的边界由着色器的倒角与球冠明暗建模出来，再压一圈线会读成"贴了个框"。
 * 判据与 `metalCard` 一致：金属档自己已经建过边缘。
 */
@Composable
private fun Modifier.pillEdge(
    shape: RoundedCornerShape,
    colors: CourseColors,
): Modifier =
    if (LocalCoursePreset.current.metal != null) {
        this
    } else {
        border(width = 1.dp, color = colors.border, shape = shape)
    }

/**
 * 语气图标：一枚**挖空**的圆徽，字形用胶囊自己的面色画出来。
 *
 * ## 为什么不是直接给图标染色
 * 上一版把 `Filled.Check` 与 `Filled.Close` 用同一个 13dp 直接染成语气色。
 * 问题在字形的**墨量**：对勾是一笔斜线，叉号是两笔交叉——同样 13dp，
 * 叉号在视觉上明显更重。于是失败提示天然显得更"吵"，成功提示显得更"虚"，
 * 而同一条通道的两种结局本该等重。
 *
 * 圆徽把两者的差别压回到"字形内容"这一层：外面那枚实心圆提供**完全相同**的
 * 面积与轮廓，字形只负责说明是哪一种结局。
 *
 * ## 为什么字形取胶囊面色而不是白色
 * 白色在暖色主题上压陶土橘只有 2.6:1；而语气色本身已经解到"与胶囊面 ≥3.0"，
 * 反过来用胶囊面色画字形，对比度就是同一个 3.0 以上——不用再解一次，
 * 也不会在某套预设上掉到看不见。
 *
 * ## 为什么圆徽比字形大
 * 11dp 的字形放进 16dp 的圆里，四周各留 2.5dp。这是"挖空"读得出来的最小留白：
 * 再紧一点，叉号的四个端点会顶到圆周，读成一枚被划掉的圆而不是一枚徽。
 */
@Composable
private fun IconBadge(
    icon: ImageVector,
    tint: Color,
    face: Color,
) {
    Box(
        modifier = Modifier.size(BadgeSize).clip(RoundedCornerShape(percent = 50)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = face,
            modifier = Modifier.size(BadgeGlyphSize),
        )
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
 * ## 轨道为什么不用 colors.border
 * border 只需要在同色系里划一条线，本来就该淡（实测 12 组预设压胶囊面只有 1.22~1.55:1）。
 * 进度条要回答的是"总共有多少"，轨道看不见时用户看到的是一根无缘无故变长的色条。
 * 所以轨道色由 [IslandTokens.trackOn] 解到 1.5:1 以上——看得见、但退在已走段后面。
 *
 * ## 两段为什么都是圆头
 * 已走段左端贴齐轨道左端、右端是"当前时刻"；方头会把这两个意思画成同一个直角。
 * 右端圆头读作「进度推进到这里」，与胶囊家族其余控件（开关、指示器）的圆润语言一致。
 */
@Composable
private fun IslandHintCountdown(
    barColor: Color,
    trackColor: Color,
    progress: () -> Float,
) {
    val barShape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BarInsetX)
            .height(BarHeight)
            .clip(barShape)
            .background(trackColor)
            .drawBehind {
                val w = size.width * progress().coerceIn(0f, 1f)
                if (w <= 0f) return@drawBehind
                // 宽度还不到一个圆时整段画成一个圆：此时"圆头"就是条本身，
                // 再去减半径会把圆心推到左边、被 clip 啃掉一块
                val r = size.height / 2f
                if (w <= size.height) {
                    drawCircle(color = barColor, radius = w / 2f, center = Offset(w / 2f, r))
                } else {
                    drawRect(color = barColor, size = Size(width = w - r, height = size.height))
                    drawCircle(color = barColor, radius = r, center = Offset(w - r, r))
                }
            },
    )
}

/** 胶囊上沿留白 */
private val PillTopPad = 11.dp

/** 胶囊下沿留白：比上沿多一点，进度条才不会贴边 */
private val PillBottomPad = 9.dp

/**
 * 胶囊横向留白（文字左缘与进度条两端共用）。
 *
 * 三处取同一个值不是巧合：进度条是这颗胶囊的**仪表**，它的两端必须和文字对齐，
 * 才读作"一条刻度"。上一版进度条用了 20dp、文字用了 18dp，两者相差 2dp——
 * 单看不明显，但胶囊两端是半圆，那 2dp 会让条的两头分别探出去一点点，
 * 读起来像没对齐。胶囊端点本身在弧线之外，18dp 已足够避开半径。
 */
private val BarInsetX = 18.dp

/** 文字行左起留白：与 [BarInsetX] 同一个值 */
private val TextStartPad = 18.dp

/** 图标与文字之间 */
private val IconGap = 8.dp

/** 圆徽直径：16dp 是「比文字高一档、但不抢行高」的量级（正文行高 17sp） */
private val BadgeSize = 16.dp

/**
 * 徽内字形尺寸。
 *
 * 11dp 是 [BadgeSize] 的 69%：留出 2.5dp 的四周边距，挖空才读得出来。
 * 再大一点，叉号的两个端点会顶到圆周。
 */
private val BadgeGlyphSize = 11.dp

/** 文字与进度条之间 */
private val TextBarGap = 7.dp
