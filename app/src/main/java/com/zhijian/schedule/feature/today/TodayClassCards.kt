package com.zhijian.schedule.feature.today
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.cardTilt
import com.zhijian.schedule.core.ui.cardTiltGlare
import com.zhijian.schedule.core.ui.cardTiltParallax
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard
import com.zhijian.schedule.core.ui.rememberCardTiltState
import com.zhijian.schedule.core.ui.rememberFocusCardTiltStyle
import com.zhijian.schedule.core.ui.rememberMotionAllowed
import com.zhijian.schedule.core.util.TimeUtils
import java.time.Duration
import java.time.LocalDateTime

/** 关掉动画时「正在上课」色点的固定不透明度：比呼吸最低值亮，色点仍然一眼可辨 */
private const val LIVE_DOT_STATIC_ALPHA = 0.85f

@Composable
internal fun CurrentClassCard(
    item: TodayCourseItem,
    clock: State<LocalDateTime>,
    /**
     * 时间条是否做那一次「从全亮扫到真实进度」的入场动作。
     *
     * 传的是宿主那个「进程内第一次进入今日」的闸门：为 true 时扫一遍，之后进入直接落位。
     * 之所以不能只靠 remember —— 卡片每次离开组合都会新建 Animatable（初值 1f），
     * 于是切走再回来又会从头扫一遍，读起来就是「时间条每次都在动」。
     */
    sweepOnce: Boolean = true,
) {
    val now = clock.value.toLocalTime()
    val start = item.startTime ?: return
    val end = item.endTime ?: return
    val total = Duration.between(start, end).seconds.coerceAtLeast(1)
    val passed = Duration.between(start, now).seconds.coerceIn(0, total)
    val remaining = 1f - passed.toFloat() / total
    val remainMin = (total - passed) / 60
    // 与课表色块共用**同一套派生**：container 铺底 / line 描边 / ink 正文 / inkMuted 次要。
    // 旧实现直接拿源色加 7% 透明度铺底，饱和色被 α 洗成同一种灰白，
    // 而且和课表里那块（OKLCH 派生、色相保留）对不上色——同一门课两个页面两种颜色。
    val isDark = LocalIsDark.current
    val tone = remember(item.course.color, isDark) { courseTone(Color(item.course.color), isDark) }
    val shape = RoundedCornerShape(18.dp)
    // 「我的」页学期概览卡那套交互原样复刻到这张焦点卡上：指腹透视倾动 + 径向流光 +
    // 前景视差 + 按下抬升，两者共用同一份取值（rememberFocusCardTiltStyle），手感是同一块纸片
    val tilt = rememberCardTiltState()
    val tiltStyle = rememberFocusCardTiltStyle()
    val motionAllowed = rememberMotionAllowed()
    // 金属主题判定：焦点卡要用金属板而不是 tone.container 铺底
    val preset = LocalCoursePreset.current
    val sectionText =
        when {
            item.course.startSection <= 0 -> null
            item.course.startSection == item.course.endSection -> "第${item.course.startSection}节"
            else -> "第${item.course.startSection}-${item.course.endSection}节"
        }

    // 时间条：只在「进程内第一次进入今日」时做一次入场扫描（全亮 → 真实进度，900ms），
    // 之后每次进入直接落在当前进度上，不再自己动。
    //
    // 旧实现是 `Animatable(1f)` + `LaunchedEffect(remaining)` 无条件开着：卡片每次离开组合
    // 都会新建一个 Animatable（初值恒为 1f），切回来又从头上扫一遍，观感是「这条一直在动」。
    // 现在初值按闸门给，而且只有首播那一次才走动画；分钟级别的推进直接赋值——
    // 时钟本来是一分钟一跳，把它做成 900ms 的缓动只是「一秒里假装在走」，没有信息量。
    val animatedOnce = remember { sweepOnce }
    // 扫描是否已经结束：首播那一轮跑完才置 true，避免「分钟跳变」把正在播的扫描打断
    val swept = remember { mutableStateOf(!animatedOnce) }
    val progressAnim = remember { Animatable(if (animatedOnce) 1f else remaining) }
    LaunchedEffect(animatedOnce) {
        if (animatedOnce) {
            progressAnim.animateTo(remaining, tween(durationMillis = 900, easing = Motion.EaseOut))
            swept.value = true
        } else {
            progressAnim.snapTo(remaining)
        }
    }
    // 之后每分钟的推进直接换值：没有动画，也就不会再「动」一次
    LaunchedEffect(remaining) {
        if (swept.value) progressAnim.snapTo(remaining)
    }

    // 「正在上课」色点呼吸脉冲：在动的才叫进行中。
    // reduce-motion 下停在 0.85（比最低值亮，色点依然可辨），不删色点——
    // 「正在上课」是当前状态的事实，"在闪"只是它的强调方式。
    val dotPulse =
        if (motionAllowed) {
            val live = rememberInfiniteTransition(label = "liveClass")
            live.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1100),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "liveDot",
            )
        } else {
            remember { mutableStateOf(LIVE_DOT_STATIC_ALPHA) }
        }

    Column(
        Modifier
            .fillMaxWidth()
            // 倾动/抬升在最外层图层：clip 之后的背景与流光都跟着卡面一起转
            .cardTilt(state = tilt, shape = shape, style = tiltStyle, enabled = motionAllowed)
            .clip(shape)
            // 金属主题：焦点卡是一块抛光到会反光的彩色金属片（RAISED）；其余主题保持 tone.container 铺底
            .let { base ->
                if (preset.metal != null) {
                    base.metalCard(shape = shape, color = tone.container, tone = CardTone.RAISED)
                } else {
                    base.background(tone.container, shape)
                }
            }
            // 流光必须压在背景之后、文字之前：只提亮卡面，不洗淡课名与倒计时。
            // 高光/暗面取本课的同色相色调：白光会把绿色卡面洗成白斑，黑罩会把绿色压成脏橄榄
            .cardTiltGlare(
                state = tilt,
                shape = shape,
                style = tiltStyle,
                highlight = tone.highlight,
                shade = tone.shade,
            ).border(1.dp, tone.line, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.cardTiltParallax(state = tilt, style = tiltStyle, depth = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tone.ink.copy(alpha = dotPulse.value)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在上课",
                color = tone.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            sectionText?.let {
                Text(
                    text = it,
                    color = tone.ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tone.ink.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 前景文字按 Apple 图层位移表动得最多；下面的进度条是"事实"（真实时间），不参与视差
        Text(
            text = item.course.name,
            style = MaterialTheme.typography.titleLarge,
            color = tone.ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.cardTiltParallax(state = tilt, style = tiltStyle, depth = 12.dp),
        )
        val info =
            listOf(item.course.room, item.course.teacher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        if (info.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = info,
                style = MaterialTheme.typography.bodyMedium,
                color = tone.inkMuted,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.cardTiltParallax(state = tilt, style = tiltStyle, depth = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = remainMin.toString(),
                color = tone.ink,
                fontSize = 40.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "分钟后下课",
                fontSize = 13.sp,
                color = tone.inkMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progressAnim.value },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            color = tone.ink,
            trackColor = tone.line.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                text = "${TimeUtils.formatTime(start)} 开始",
                fontSize = 11.sp,
                color = tone.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${TimeUtils.formatTime(end)} 下课",
                fontSize = 11.sp,
                color = tone.inkMuted,
            )
        }
    }
}

@Composable
internal fun NextClassCard(
    item: TodayCourseItem,
    clock: State<LocalDateTime>,
) {
    val now = clock.value.toLocalTime()
    val colors = LocalCourseColors.current
    val start = item.startTime ?: return
    val end = item.endTime
    val minutes = Duration.between(now, start).toMinutes()
    // 与 CurrentClassCard / 课表色块同一套派生，这门课的"身份色"两处必然一致
    val isDark = LocalIsDark.current
    val preset = LocalCoursePreset.current
    val tone =
        remember(item.course.color, isDark, preset.toneBias) {
            courseTone(Color(item.course.color), isDark, preset.toneBias)
        }
    val shape = RoundedCornerShape(14.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .let { base ->
                if (preset.metal != null) {
                    base.metalCard(shape = shape, color = tone.container, tone = CardTone.SURFACE)
                } else {
                    base.background(tone.container, shape)
                }
            }.border(1.dp, tone.line.copy(alpha = if (preset.metal != null) 0.45f else 1f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tone.ink),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "下一节 · ${item.course.name}",
                style = MaterialTheme.typography.titleSmall,
                color = tone.ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text =
                    buildString {
                        if (item.course.room.isNotBlank()) append(item.course.room)
                        val range =
                            buildString {
                                append(TimeUtils.formatTime(start))
                                end?.let { append("–").append(TimeUtils.formatTime(it)) }
                            }
                        if (isNotEmpty()) append(" · ")
                        append(range)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = tone.inkMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (minutes > 0) "${TimeUtils.minutesText(minutes)}后" else "即将开始",
            color = colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
