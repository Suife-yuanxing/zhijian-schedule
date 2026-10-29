package com.zhijian.schedule.feature.today
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.feature.exam.ExamCountdown
import java.time.LocalDateTime

/** 最近考试倒计时卡：14 天内出现在「下一节」之后，点击进考试页 */
@Composable
internal fun ExamCountdownCard(
    exam: ExamEntity,
    clock: State<LocalDateTime>,
    onClick: () -> Unit,
) {
    // 只有这张卡需要「天」级时间，自己订阅，页面的其余部分不跟着动
    val now = clock.value
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ExamCountdown.cardTitle(exam.name, exam.examTime, now),
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            val where =
                listOf(
                    exam.room.takeIf { it.isNotBlank() },
                    exam.seat.takeIf { it.isNotBlank() }?.let { "$it 号座" },
                ).filterNotNull().joinToString(" · ")
            Text(
                listOfNotNull(ExamCountdown.detailLine(exam.examTime), where.ifBlank { null })
                    .joinToString(" · "),
                color = colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textMuted.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}
