package com.zhijian.schedule.feature.schedule.components
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
import com.zhijian.schedule.core.ui.rememberAnimationsDisabled
import com.zhijian.schedule.core.ui.rememberBreathingAlpha
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
    // 呼吸灯：值**只在绘制期读**（drawBehind），每次采样只失效绘制，组合不参与。
    //
    // 节拍换成了定频采样（见 rememberBreathingAlpha / PulseMath）：曲线与 1800ms 一个来回
    // 都不变，但不再是「无限动画逐帧推」。旧写法让课表页**静置**就恒在 ~62fps 重绘
    // （真机 10 秒 621 帧），而那正是切页时和整屏滑动抢渲染管线的那份开销。
    //
    // reduce-motion：系统关掉动画时直接给一个固定值（略高于静息的最低值），
    // 而不是把整条时间线也删掉——「现在几点」是事实，只是不再闪。
    val animationsDisabled = rememberAnimationsDisabled()
    val pulse: State<Float> = rememberBreathingAlpha(animationsDisabled)
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
