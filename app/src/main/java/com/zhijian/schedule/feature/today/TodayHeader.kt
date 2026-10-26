package com.zhijian.schedule.feature.today
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.util.TimeUtils

/** 今日页页头：日期标题 + 「第 N 周」徽章；节假日徽章与标题同排。 */
@Composable
internal fun TodayHeader(ui: TodayUiState) {
    val colors = LocalCourseColors.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "今天 · ${TimeUtils.dayName(TimeUtils.dayOfWeek(ui.today))}",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            ui.todayHoliday?.let { h ->
                Spacer(Modifier.width(10.dp))
                val tint = if (h.isOffDay) HolidayColors.REST else HolidayColors.WORK
                Text(
                    text = "${h.name} · ${if (h.isOffDay) "休" else "班"}",
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tint.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("${ui.today.monthValue}月${ui.today.dayOfMonth}日")
                    if (ui.timetableName.isNotBlank()) append(" · ${ui.timetableName}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ui.week?.let { week ->
                Text(
                    text = "第 $week 周",
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
}
