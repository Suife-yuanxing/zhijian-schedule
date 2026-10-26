package com.zhijian.schedule.core.ui
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import kotlin.math.abs

/** 轨道尺寸：取 M3 Switch 的 52×32。 */
private val TrackWidth = 52.dp
private val TrackHeight = 32.dp

/**
 * 手柄直径：未选中 16dp，选中 24dp。
 *
 * 取的是 M3 Switch 规范里的手柄高度分档（16 / 24 / 28dp 三档，第三档是按下态）。
 * 这不是装饰：M3 用「大小」当第二个状态通道，关是**一颗小圆点**、开是**一枚满手柄**，
 * 于是「关→开」读作一次**充气**，而不只是「换个颜色 + 挪个位置」。
 * 本组件把它接在果冻上：小圆点被拉长成囊形飞出去，落到对面时已经长满——
 * 「小水滴飞过去长成一枚球」，形变和尺寸变化是同一个动作的两面。
 *
 * 为什么不用第三档（按下 28dp）：按压反馈已经交给 [JellyGrab] 的横向拉伸了。
 * 尺寸和拉伸同时长会让「按住了」和「在飞了」两件事糊在一起；而且 24dp 的手柄
 * 拉满 45% 之后正好是 ~28dp，等于顺手把 M3 的按下档也覆盖了，不必再加一条通道。
 */
private val ThumbOff = 16.dp
private val ThumbOn = 24.dp

/** 圆点换色时长：快于位移，保证「胶囊拉满」那一帧圆点已经是目的地颜色。 */
private const val JellyThumbTintMs = 120

/** 轨道换色时长：与圆点同档，位移还没走到一半，底色就已经是终态。 */
private const val JellyTrackTintMs = 140

/** 未选中描边的淡入淡出时长：必须与 [JellyTrackTintMs] 同拍，否则会看到「蓝底上挂着一圈灰边」。 */
private const val JellyBorderMs = 140

/** 禁用态整体压暗：M3 用 0.38 的内容透明度，轨道更淡，这里折中取 0.42。 */
private const val JellyDisabledAlpha = 0.42f

/**
 * 果冻开关：点一下，圆点滑向另一端，途中被「速度」拉长成胶囊，到站再弹回圆形。
 *
 * ## 形变是速度算出来的，不是排好的关键帧
 * 拉伸量直接取圆点的**实时速度**（[jellyStretch]），而不是另起一条 0→1 的形变动画。
 * 速度与位移天生同拍，永远不会出现「胶囊已经缩回去了、圆点还在路上」的错拍；
 * 落位前弹簧的过冲让速度反向，胶囊跟着反向收，收完就是正圆——「回弹恢复圆形」是弹簧的
 * 副产物，不需要再补一条动画。按住时圆点先被捏宽一点（[JellyGrab]），是手指的抓取感。
 *
 * ## 两条时间线
 * | 通道 | 弹簧 | 作用 |
 * | --- | --- | --- |
 * | 位置（0↔1） | [Motion.JellySlide] ζ=0.55 / k=420 | 滑行 + 落位过冲 12.6% |
 * | 直径（16↔24dp） | [Motion.JellyGrow] ζ=0.62 / k=620 | 比滑行更快收口，拉伸高峰时手柄已近终态 |
 *
 * 直径**不能**直接挂在位置上：位置弹簧过冲 12.6%，尺寸会跟着弹到 25dp 再缩回来，
 * 读作「手柄在终点抖了一下」。两条时间线各自收敛，只是同时出发。
 *
 * ## 为什么画胶囊而不是缩放圆
 * 对圆做 scaleX 得到的是**椭圆**，两端不是半圆；胶囊要求圆角半径恒等于高度的一半。
 * 所以这里宽、高两个通道各自演化，圆角恒取 高/2（[jellyThumbRect]）：
 * 拉伸时宽变宽、高变矮，两端始终是正半圆，才是「被拉长的水滴」而不是「压扁的蛋」。
 *
 * ## 体积守恒与「撞墙」
 * 纵向压缩取 `max(线性上限, 面积守恒)`：常态下线性项更大，保留调好的 26% 压缩；
 * 一旦宽度被轨道边界夹窄，面积守恒项接管，手柄反而**变高**——撞墙该读作「被压扁」
 * （窄而高），而不是「缩水」（又窄又矮）。这条分支在常规 0↔1 切换里不会触发：
 * 逐帧积分显示过冲 12.6% 时囊形鼻尖离轨道壁还有约 1.5dp，是**差一点就顶到**的观感，
 * 夹紧只是快速连点时的保险。
 *
 * ## 参数依据
 * 弹簧 [Motion.JellySlide] 阻尼比 0.55，20dp 行程上过冲约 2.5dp——小于选中态 4dp 的内边距，
 * 回弹看得见、又顶不出轨道。纵向压缩 26%：再多会像进度条，再少读不出「被拉长」。
 * 拉伸峰值出现在 t ≈ 50ms、宽度约为轨道的 58%。
 *
 * ## 无障碍
 * - 触控热区由 [minimumInteractiveComponentSize] 撑到 48dp，视觉仍是 32dp 高；
 * - 语义走 [toggleable] + [Role.Switch]，TalkBack 读作「开关，已选中/未选中」；
 * - 系统关掉动画（无障碍「移除动画」/ 开发者选项 Animator scale = 0）时直接落位、不做形变。
 * - 不加触觉：仓库约定（见 [Motion.buttonFeedback] 的 KDoc）开关这类高频操作不震，
 *   否则全局每个按钮都震会让触感失去信息量。
 */
@Composable
fun JellySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalCourseColors.current
    val motionAllowed = rememberMotionAllowed()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 位置进度：0 = 关（左端），1 = 开（右端）。首次组合直接落在目标值，不播入场动画。
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    // 直径进度：与位置同起点、不同弹簧——形变要演出「飞」，尺寸要「到站即定」。
    val grow = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, motionAllowed) {
        val target = if (checked) 1f else 0f
        if (motionAllowed) {
            progress.animateTo(target, Motion.JellySlide)
        } else {
            progress.snapTo(target)
        }
    }
    LaunchedEffect(checked, motionAllowed) {
        val target = if (checked) 1f else 0f
        if (motionAllowed) {
            grow.animateTo(target, Motion.JellyGrow)
        } else {
            grow.snapTo(target)
        }
    }

    val grab by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = Motion.PressSpring,
        label = "jellyGrab",
    )
    // 轨道/圆点颜色跟着主题走：选中 = accent 底 + onAccent 圆点，未选中 = surfaceVariant 底 + textMuted 圆点。
    // 轨道换色要**快**：M3 的开关也是 100ms 级。轨道先定色、圆点再飞过去，
    // 飞行途中的那一帧才是「白胶囊落在蓝底上」；轨道要是慢半拍，就会看到「灰胶囊卡在蓝条上」。
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceVariant,
        animationSpec = tween(JellyTrackTintMs, easing = Motion.EaseOut),
        label = "jellyTrack",
    )
    // 圆点的换色要**抢在轨道前面**：圆点是主角，飞行途中它还灰着、轨道已经蓝了，
    // 那一帧读起来像「灰团卡在蓝条上」。120ms 内换完，胶囊拉满时已经是目的地那一色。
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.onAccent else colors.textMuted,
        animationSpec = tween(JellyThumbTintMs, easing = Motion.EaseOut),
        label = "jellyThumb",
    )
    // 未选中补一道 hairline：surfaceVariant 与卡片底色只差一档，没有描边会「浮」不起来。
    // 描边必须**跟着轨道颜色一起**淡入淡出：上一版按 `if (!checked)` 硬切，关的一瞬间
    // 轨道还是蓝的、灰边已经画上去了，会闪出一圈「蓝底挂灰框」。
    val borderAlpha by animateFloatAsState(
        targetValue = if (checked) 0f else 1f,
        animationSpec = tween(JellyBorderMs, easing = Motion.EaseOut),
        label = "jellyBorder",
    )
    val borderColor = colors.border

    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(TrackWidth, TrackHeight)
            .graphicsLayer { alpha = if (enabled) 1f else JellyDisabledAlpha }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .drawBehind {
                val track = size
                val trackRadius = CornerRadius(track.height / 2f)
                drawRoundRect(color = trackColor, cornerRadius = trackRadius)
                if (borderAlpha > 0.01f) {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = borderColor.copy(alpha = borderColor.alpha * borderAlpha),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(track.width - stroke, track.height - stroke),
                        cornerRadius = CornerRadius(track.height / 2f - stroke / 2f),
                        style = Stroke(stroke),
                    )
                }
                // 先读 value 再读 velocity：两者同源，同一帧的形变不会落后于位移
                val position = progress.value
                val speed = abs(progress.velocity)
                val p = if (rtl) 1f - position else position
                val diameter = (ThumbOff + (ThumbOn - ThumbOff) * grow.value).toPx()
                val thumb = jellyThumbRect(
                    track = track,
                    progress = p,
                    stretch = jellyStretch(speed, grab),
                    diameter = diameter,
                    maxStretch = diameter * JellyStretchRatio,
                    squash = JellySquash,
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(thumb.left, thumb.top),
                    size = Size(thumb.width, thumb.height),
                    // 圆角恒等于高度的一半 → 两端永远是正半圆（胶囊），不是椭圆
                    cornerRadius = CornerRadius(thumb.height / 2f),
                )
            },
    )
}
