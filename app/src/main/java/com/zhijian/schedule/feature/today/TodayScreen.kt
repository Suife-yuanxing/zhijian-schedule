package com.zhijian.schedule.feature.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.CourseDetailSheet
import com.zhijian.schedule.navigation.IslandContentPadding
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
    // 面板目标可见性：关闭动作一开始即置回 false，让行高亮与面板滑出并行恢复
    var detailSheetVisible by remember { mutableStateOf(false) }

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

        Spacer(Modifier.height(18.dp))

        ui.todayHoliday?.takeIf { it.isOffDay && ui.courses.isNotEmpty() }?.let { h ->
            StaggerIn(index = 0) {
                HolidayNoticeCard(h)
            }
            Spacer(Modifier.height(14.dp))
        }

        when {
            ui.courses.isEmpty() -> StaggerIn(index = 0) { EmptyToday(ui) }
            else -> {
                current?.let { item ->
                    StaggerIn(index = 0) {
                        CurrentClassCard(item, now)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                next?.let { item ->
                    StaggerIn(index = 1) {
                        NextClassCard(item, now)
                    }
                    Spacer(Modifier.height(18.dp))
                }
                val groups = DayPart.entries.mapNotNull { part ->
                    val entries = ui.courses.withIndex()
                        .filter { dayPartOf(it.value.startTime) == part }
                    if (entries.isEmpty()) null else part to entries
                }
                var staggerIndex = 2
                StaggerIn(index = staggerIndex++) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "今日课程",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textMuted,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${ui.courses.size} 节",
                            fontSize = 11.sp,
                            color = colors.textMuted.copy(alpha = 0.8f),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                groups.forEach { (part, entries) ->
                    StaggerIn(index = staggerIndex++) {
                        DayPartHeader(part, entries.size)
                    }
                    entries.forEach { (_, item) ->
                        StaggerIn(index = staggerIndex++) {
                            CourseRow(
                                item = item,
                                now = now,
                                isCurrent = item == current,
                                selected = detailSheetVisible && item.course.id == selectedCourse?.id,
                                onClick = {
                                    selectedCourse = item.course
                                    detailSheetVisible = true
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(IslandContentPadding))
    }

    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onTargetVisibilityChange = { detailSheetVisible = it },
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
    val remaining = 1f - passed.toFloat() / total
    val remainMin = (total - passed) / 60
    val courseColor = Color(item.course.color)
    val shape = RoundedCornerShape(18.dp)
    val sectionText = when {
        item.course.startSection <= 0 -> null
        item.course.startSection == item.course.endSection -> "第${item.course.startSection}节"
        else -> "第${item.course.startSection}-${item.course.endSection}节"
    }

    // 全亮起步、从右端往左被吃掉；此后每 15s 时钟刷新时平滑推进
    val progressAnim = remember { Animatable(1f) }
    LaunchedEffect(remaining) {
        progressAnim.animateTo(remaining, tween(durationMillis = 900, easing = Motion.EaseOut))
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
            .background(lerp(colors.surface, courseColor, 0.07f), shape)
            .border(1.dp, courseColor.copy(alpha = 0.28f), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(courseColor.copy(alpha = dotPulse))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在上课",
                color = courseColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            sectionText?.let {
                Text(
                    text = it,
                    color = courseColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(courseColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.course.name,
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        val info = listOf(item.course.room, item.course.teacher)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (info.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = info,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = remainMin.toString(),
                color = courseColor,
                fontSize = 40.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "分钟后下课",
                fontSize = 13.sp,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progressAnim.value },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = courseColor,
            trackColor = colors.border.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                text = "${TimeUtils.formatTime(start)} 开始",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${TimeUtils.formatTime(end)} 下课",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun NextClassCard(item: TodayCourseItem, now: LocalTime) {
    val colors = LocalCourseColors.current
    val start = item.startTime ?: return
    val end = item.endTime
    val minutes = Duration.between(now, start).toMinutes()
    val courseColor = Color(item.course.color)
    val shape = RoundedCornerShape(14.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(courseColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "下一节 · ${item.course.name}",
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    if (item.course.room.isNotBlank()) append(item.course.room)
                    val range = buildString {
                        append(TimeUtils.formatTime(start))
                        end?.let { append("–").append(TimeUtils.formatTime(it)) }
                    }
                    if (isNotEmpty()) append(" · ")
                    append(range)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
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
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(colors.accent.copy(alpha = 0.12f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

private enum class DayPart(val label: String) {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚上"),
}

private fun dayPartOf(time: LocalTime?): DayPart = when {
    time != null && time.hour < 12 -> DayPart.MORNING
    time != null && time.hour >= 18 -> DayPart.EVENING
    else -> DayPart.AFTERNOON
}

/** 上午 / 下午 / 晚上分隔：小标题 + 细线，让长列表有呼吸和节奏 */
@Composable
private fun DayPartHeader(part: DayPart, count: Int) {
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

@Composable
private fun CourseRow(
    item: TodayCourseItem,
    now: LocalTime,
    isCurrent: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val finished = item.endTime != null && now.isAfter(item.endTime)
    val alpha = if (finished) 0.45f else 1f
    val courseColor = Color(item.course.color)
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
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
                    if (isCurrent) colors.surfaceVariant else colors.surface,
                    colors.surfaceVariant,
                    ring,
                ),
                shape,
            )
            .border(1.dp, colors.border.copy(alpha = 0.5f), shape)
            .border(2.dp, courseColor.copy(alpha = 0.9f * ring), shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(courseColor.copy(alpha = alpha))
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
                color = courseColor,
                background = courseColor.copy(alpha = 0.12f),
            )
        }
    }
}
