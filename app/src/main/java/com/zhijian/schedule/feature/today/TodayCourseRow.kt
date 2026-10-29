package com.zhijian.schedule.feature.today
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.rememberTapHaptic
import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDateTime
import java.time.LocalTime

internal enum class DayPart(val label: String) {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚上"),
}

internal fun dayPartOf(time: LocalTime?): DayPart = when {
    time != null && time.hour < 12 -> DayPart.MORNING
    time != null && time.hour >= 18 -> DayPart.EVENING
    else -> DayPart.AFTERNOON
}

@Composable
internal fun CourseRow(
    item: TodayCourseItem,
    clock: State<LocalDateTime>,
    isCurrent: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 行状态徽章（已结束/进行中）是本页最"密"的时间读者，让它自己订阅，
    // 一分钟只重算这一行，不再牵动整页
    val now = clock.value.toLocalTime()
    val colors = LocalCourseColors.current
    val finished = item.endTime != null && now.isAfter(item.endTime)
    val alpha = if (finished) 0.45f else 1f
    // 行也用课表色块的派生色：整页「这门课是什么颜色」只有一套算法
    val isDark = LocalIsDark.current
    val preset = LocalCoursePreset.current
    val tone = remember(item.course.color, isDark, preset.toneBias) {
        courseTone(Color(item.course.color), isDark, preset.toneBias)
    }
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val tapHaptic = rememberTapHaptic()
    val shape = RoundedCornerShape(12.dp)

    // 详情打开期间「弹起 + 描边 + 填充加深」；关闭动作一开始即快速回落（与面板滑出并行）
    val lift by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) Motion.SelectionEnter else Motion.SelectionExit,
        label = "rowLift",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.PressSpring,
        label = "rowPress",
    )
    val ring = lift.coerceIn(0f, 1f)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                val scale = press * (1f + 0.02f * lift)
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
            }
            .background(
                lerp(
                    // 底色一律用原色：课表里的色块不随时间褪色，今日页也得是同一个色
                    // （「已结束」只靠文字透明度和角标表达，不去改底色，否则同一门课两处两种颜色）
                    tone.container,
                    tone.line,
                    0.45f * ring,
                ),
                shape,
            )
            .border(1.dp, tone.line.copy(alpha = 0.7f), shape)
            .border(2.dp, tone.ink.copy(alpha = 0.9f * ring), shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = {
                    tapHaptic()
                    onClick()
                },
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tone.ink.copy(alpha = alpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(80.dp)) {
            Text(
                text = item.startTime?.let { TimeUtils.formatTime(it) } ?: "—",
                fontSize = 12.sp,
                color = colors.text.copy(alpha = alpha),
                fontWeight = FontWeight.Medium,
            )
            item.endTime?.let {
                Text(
                    text = TimeUtils.formatTime(it),
                    fontSize = 11.sp,
                    color = colors.textMuted.copy(alpha = alpha),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.course.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text.copy(alpha = alpha),
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            val sub = listOf(item.course.room, item.course.teacher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    color = colors.textMuted.copy(alpha = alpha),
                    maxLines = 1,
                )
            }
        }
        if (finished) {
            StatusPill(
                text = "已结束",
                color = colors.textMuted,
                background = colors.border.copy(alpha = 0.18f),
            )
        } else if (isCurrent) {
            StatusPill(
                text = "进行中",
                color = tone.ink,
                background = tone.ink.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color,
    background: Color,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
