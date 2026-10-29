package com.zhijian.schedule.feature.schedule.components
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.ScheduleUiState

/** 表头：周几 + 日期。无课程（空状态）时不留时间轴空槽，避免左侧空白突兀 */
@Composable
fun WeekHeader(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    /**
     * 「今天」。收 State 而不是 LocalDate：整分时钟每分钟都会换一个新值，
     * 若在页面级解包，整个课表页会跟着分钟重组一遍；这里只在跨零点时变一次。
     */
    today: State<java.time.LocalDate>,
    alignToTimeAxis: Boolean = true,
    onDayClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    metrics: GridMetrics = GridMetrics.Default,
) {
    val colors = LocalCourseColors.current
    // 唯一读「今天」的地方：跨零点才失效，分钟跳变不会碰表头
    val todayDate = today.value
    Row(
        modifier
            .fillMaxWidth()
            .height(metrics.headerHeight),
    ) {
        if (alignToTimeAxis) {
            Spacer(Modifier.width(metrics.timeAxisWidth))
        }
        visibleDays.forEach { day ->
            val date = ui.dateOf(week, day)
            val isToday = date == todayDate
            val holiday = ui.holidays[date.toString()]
            val pressSource = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(pressSource, pressedScale = 0.94f)
                    .clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                    ) { onDayClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 今天不用 accent 上色：accent 对底色的对比只有 2.9:1，承载 11sp 会掉到 AA 线下
                Text(
                    text = TimeUtils.dayName(day),
                    color = if (isToday) colors.text else microText(colors),
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                // 今天用一圈 accent 环标记日期，环不承载文字，不必满足文字对比要求
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .then(if (isToday) Modifier.border(1.2.dp, colors.accent, CircleShape) else Modifier)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${date.dayOfMonth}",
                        color = colors.text,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    )
                }
                if (holiday != null) {
                    Text(
                        text = if (holiday.isOffDay) "休" else "班",
                        color = if (holiday.isOffDay) HolidayColors.REST else HolidayColors.WORK,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
