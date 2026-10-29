package com.zhijian.schedule.feature.schedule.components
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.ScheduleUiState
import java.time.LocalDateTime

/**
 * 当前时间标记（仅本周可见时）：主题色的横线 + 端点 + 时间轴里的时间胶囊。
 * 旧实现硬编码 #E5484D，且落在课间时整段消失。
 */
@Composable
internal fun NowMarker(
    ui: ScheduleUiState,
    week: Int,
    clock: State<LocalDateTime>,
    layout: GridLayout,
    visibleDays: List<Int>,
    metrics: GridMetrics,
) {
    val colors = LocalCourseColors.current
    if (ui.currentWeek != week) return
    // 订阅从这一行才开始：pager 预组合的邻页里这条线根本不画，也就不读分钟
    val now = clock.value
    val todayDay = TimeUtils.dayOfWeek(now.toLocalDate())
    // 今天那一列没显示（关掉周末 / 调休周）时，画一条横贯全表的线会指向不存在的那天
    if (todayDay !in visibleDays) return
    if (TimeUtils.dateOfWeekDay(ui.startDate, week, todayDay) != now.toLocalDate()) return
    val slot = layout.timeSlot(now.toLocalTime()) ?: return

    // 用 accent 自己派生的「深色调」：accent 原色对底色只有 2.9:1，不能直接写字
    val accentTone = courseTone(colors.accent, LocalIsDark.current)
    // 呼吸灯：曲线与节奏保持原样（1.8s 线性往返），但值**只在绘制期读**。
    // 原来 pulse 被读进 Modifier.background(...) 里，等于每帧重组一次 NowMarker + 重建两个
    // Modifier；改成 drawBehind 里读 State 之后，每帧只失效绘制，组合不再参与。
    // （顺带一个实测事实：这条呼吸灯让课表页**静置**就恒在 ~62fps 重绘，12s 画 741 帧。
    //  这是本页最贵的固定开销；要彻底归零只能去掉呼吸效果，属于设计取舍，没动。）
    val breath = rememberInfiniteTransition(label = "nowMarker")
    val pulse =
        breath.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "nowMarkerPulse",
        )
    val lineAlphaBase = if (slot.inClass) 0.72f else 0.4f

    // 时间轴里的时间胶囊：宽度吃满时间轴，高度随网格一起放大（字体放大后 16dp 装不下 9sp 翻倍的字）
    val box = metrics.boxScale
    Box(
        Modifier
            .offset(x = 2.dp, y = slot.y - 8.dp * box)
            .width(metrics.timeAxisWidth)
            .height(16.dp * box)
            .clip(RoundedCornerShape(8.dp * box))
            .background(accentTone.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = now.toLocalTime().format(HHMM),
            color = accentTone.container,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            style = TabularFigure,
        )
    }
    // 端点
    Box(
        Modifier
            .offset(x = metrics.timeAxisWidth - 3.dp * box, y = slot.y - 3.dp * box)
            .size(6.dp * box)
            .clip(CircleShape)
            .drawBehind { drawRect(accentTone.ink.copy(alpha = pulse.value)) },
    )
    // 横线
    Box(
        Modifier
            .offset(y = slot.y - 0.75.dp * box)
            .fillMaxWidth()
            .height(1.5.dp * box)
            .padding(start = metrics.timeAxisWidth)
            .drawBehind { drawRect(accentTone.ink.copy(alpha = lineAlphaBase * pulse.value)) },
    )
}
