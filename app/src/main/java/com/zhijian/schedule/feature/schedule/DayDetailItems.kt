package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate
import java.time.LocalDateTime

/** 某星期几的周次速选 pill：选中弹跳放大 + 底色/描边/文字色渐变过渡 */
@Composable
internal fun DayPill(
    date: LocalDate,
    selected: Boolean,
    isCurrentWeek: Boolean,
    past: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val popScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = Motion.PopSpring,
        label = "pillScale",
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accent
            isCurrentWeek -> colors.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        label = "pillBg",
    )
    val ring by animateColorAsState(
        targetValue = if (selected || isCurrentWeek) colors.accent else colors.border,
        animationSpec = tween(durationMillis = Motion.DurFast),
        label = "pillRing",
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> colors.onAccent
            past -> colors.textMuted.copy(alpha = 0.6f)
            else -> colors.text
        },
        animationSpec = tween(durationMillis = Motion.DurFast),
        label = "pillText",
    )

    Column(
        Modifier
            .pressScale(pressSource, pressedScale = 0.92f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, ring, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${date.monthValue}/${date.dayOfMonth}",
            fontSize = 12.sp,
            color = textColor,
        )
        Text(
            text = if (isCurrentWeek) "本周" else " ",
            fontSize = 9.sp,
            color = if (selected) colors.onAccent else colors.accent,
        )
    }
}

@Composable
internal fun DayCourseRow(
    course: CourseEntity,
    sections: List<SectionTimeEntity>,
    date: LocalDate,
    now: LocalDateTime,
) {
    val colors = LocalCourseColors.current
    val isToday = date == now.toLocalDate()
    val past = date.isBefore(now.toLocalDate())
    val startTime = sections.getOrNull(course.startSection - 1)?.startTime.orEmpty()
    val endTime = sections.getOrNull(course.endSection - 1)?.endTime.orEmpty()

    val status = if (isToday) {
        val st = TimeUtils.parseTime(startTime)
        val et = TimeUtils.parseTime(endTime)
        val t = now.toLocalTime()
        when {
            st == null || et == null -> null
            t.isAfter(et) -> "已结束"
            t.isBefore(st) -> "即将开始"
            else -> "进行中"
        }
    } else null
    val tag = status ?: if (past) "已结束" else null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .alpha(if (past) 0.55f else 1f)
            .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(course.color)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(86.dp)) {
            Text(
                text = startTime,
                fontSize = 12.sp,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(text = endTime, fontSize = 11.sp, color = colors.textMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(course.room, course.teacher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(text = sub, fontSize = 12.sp, color = colors.textMuted, maxLines = 1)
            }
        }
        if (tag != null) {
            Text(
                text = tag,
                fontSize = 11.sp,
                color = if (tag == "进行中") colors.accent else colors.textMuted,
            )
        }
    }
}
