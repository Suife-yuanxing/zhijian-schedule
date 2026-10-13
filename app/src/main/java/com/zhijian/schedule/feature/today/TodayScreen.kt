package com.zhijian.schedule.feature.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.HolidayEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.CourseDetailSheet
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.delay

@Composable
fun TodayScreen(
    onEditCourse: (Long) -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clock = LocalDateTime.now()
        }
    }

    val now = clock.toLocalTime()
    val current = ui.courses.firstOrNull { item ->
        val s = item.startTime; val e = item.endTime
        s != null && e != null && !now.isBefore(s) && !now.isAfter(e)
    }
    val next = ui.courses.firstOrNull { item ->
        item.startTime != null && now.isBefore(item.startTime)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
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
        Text(
            text = buildString {
                append("${ui.today.monthValue}月${ui.today.dayOfMonth}日")
                ui.week?.let { append(" · 第 $it 周") }
                if (ui.timetableName.isNotBlank()) append(" · ${ui.timetableName}")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
        )

        Spacer(Modifier.height(18.dp))

        ui.todayHoliday?.takeIf { it.isOffDay && ui.courses.isNotEmpty() }?.let { h ->
            StaggerIn(index = 0) {
                HolidayNoticeCard(h)
                Spacer(Modifier.height(14.dp))
            }
        }

        when {
            ui.courses.isEmpty() -> StaggerIn(index = 0) { EmptyToday(ui) }
            else -> {
                current?.let { item ->
                    StaggerIn(index = 0) {
                        CurrentClassCard(item, now)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                next?.let { item ->
                    StaggerIn(index = 1) {
                        NextClassCard(item, now)
                        Spacer(Modifier.height(18.dp))
                    }
                }
                StaggerIn(index = 2) {
                    Text(
                        text = if (current != null) "今日课程" else "今日课程（${ui.courses.size} 节）",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textMuted,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                ui.courses.forEachIndexed { i, item ->
                    StaggerIn(index = 3 + i) {
                        CourseRow(
                            item = item,
                            now = now,
                            isCurrent = item == current,
                            onClick = { selectedCourse = item.course },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = { selectedCourse = null },
            onEdit = {
                selectedCourse = null
                onEditCourse(course.id)
            },
            onDelete = {
                viewModel.deleteCourse(course.id)
                selectedCourse = null
            },
        )
    }
}

@Composable
private fun HolidayNoticeCard(holiday: HolidayEntity) {
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
private fun EmptyToday(ui: TodayUiState) {
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

@Composable
private fun CurrentClassCard(item: TodayCourseItem, now: LocalTime) {
    val colors = LocalCourseColors.current
    val start = item.startTime ?: return
    val end = item.endTime ?: return
    val total = Duration.between(start, end).seconds.coerceAtLeast(1)
    val passed = Duration.between(start, now).seconds.coerceIn(0, total)
    val progress = passed.toFloat() / total
    val remainMin = (total - passed) / 60

    // 入场从 0 扫到当前进度；此后每 15s 时钟刷新时平滑推进
    val progressAnim = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        progressAnim.animateTo(progress, tween(durationMillis = 900, easing = Motion.EaseOut))
    }

    // 「正在上课」色点呼吸脉冲：在动的才叫进行中
    val live = rememberInfiniteTransition(label = "liveClass")
    val dotPulse by live.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDot",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(item.course.color).copy(alpha = dotPulse))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在上课",
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.course.name,
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            if (item.course.room.isNotBlank()) {
                Text(
                    text = item.course.room,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = "还剩 ${remainMin} 分钟",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progressAnim.value },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(item.course.color),
            trackColor = colors.border.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun NextClassCard(item: TodayCourseItem, now: LocalTime) {
    val colors = LocalCourseColors.current
    val start = item.startTime ?: return
    val minutes = Duration.between(now, start).toMinutes()

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(
                text = TimeUtils.formatTime(start),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            item.endTime?.let {
                Text(
                    text = TimeUtils.formatTime(it),
                    fontSize = 11.sp,
                    color = colors.textMuted,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "下一节 · ${item.course.name}",
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    if (item.course.room.isNotBlank()) append(item.course.room)
                    if (minutes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${TimeUtils.minutesText(minutes)}后开始")
                    } else if (isNotEmpty()) {
                        append(" · ")
                        append("即将开始")
                    } else {
                        append("即将开始")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun CourseRow(
    item: TodayCourseItem,
    now: LocalTime,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val finished = item.endTime != null && now.isAfter(item.endTime)
    val alpha = if (finished) 0.45f else 1f
    val pressSource = remember { MutableInteractionSource() }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .pressScale(pressSource, pressedScale = 0.97f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) colors.surfaceVariant else colors.surface,
                RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(item.course.color).copy(alpha = alpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(86.dp)) {
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
            Text("已结束", fontSize = 11.sp, color = colors.textMuted)
        } else if (isCurrent) {
            Text("进行中", fontSize = 11.sp, color = colors.accent)
        }
    }
}
