package com.zhijian.schedule.feature.today
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.HolidayEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors

@Composable
internal fun HolidayNoticeCard(holiday: HolidayEntity) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(HolidayColors.REST.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "「${holiday.name}」法定假期",
            color = HolidayColors.REST,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "今日课表课程按停课处理，补课安排以学校通知为准",
            color = colors.textMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun EmptyToday(ui: TodayUiState) {
    val colors = LocalCourseColors.current
    val holiday = ui.todayHoliday
    Spacer(Modifier.height(40.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                ui.week == null -> "假期中"
                holiday?.isOffDay == true -> "「${holiday.name}」放假中"
                holiday != null -> "调休上班日"
                else -> "今天没有课"
            },
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                ui.week == null -> "不在学期周次范围内"
                holiday?.isOffDay == true -> "今日课表课程按停课处理"
                holiday != null -> "补课安排以学校通知为准"
                else -> "好好休息一下"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
    }
}

/** 上午 / 下午 / 晚上分隔：小标题 + 细线，让长列表有呼吸和节奏 */
@Composable
internal fun DayPartHeader(part: DayPart, count: Int) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = part.label,
            color = colors.textMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.border.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "$count 节",
            color = colors.textMuted.copy(alpha = 0.8f),
            fontSize = 11.sp,
        )
    }
}
