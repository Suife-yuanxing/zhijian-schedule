package com.zhijian.schedule.feature.schedule.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.ScheduleUiState
import java.time.LocalDateTime
import java.time.LocalTime

object GridDims {
    val TIME_AXIS_WIDTH: Dp = 46.dp
    val HEADER_HEIGHT: Dp = 56.dp
    val SECTION_HEIGHT: Dp = 46.dp
}

/** 表头：周几 + 日期。无课程（空状态）时不留时间轴空槽，避免左侧空白突兀 */
@Composable
fun WeekHeader(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    today: java.time.LocalDate,
    alignToTimeAxis: Boolean = true,
    onDayClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(GridDims.HEADER_HEIGHT)
            .drawBehind {
                val y = size.height - 1f
                drawLine(colors.border, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
            }
    ) {
        if (alignToTimeAxis) {
            Spacer(Modifier.width(GridDims.TIME_AXIS_WIDTH))
        }
        visibleDays.forEach { day ->
            val date = ui.dateOf(week, day)
            val isToday = date == today
            val holiday = ui.holidays[date.toString()]
            val pressSource = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pressScale(pressSource, pressedScale = 0.94f)
                    .clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                    ) { onDayClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = TimeUtils.dayName(day),
                    color = if (isToday) colors.accent else colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .background(if (isToday) colors.accent else Color.Transparent, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${date.dayOfMonth}",
                        color = if (isToday) colors.onAccent else colors.text,
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

/**
 * 周视图网格：时间轴 + 天列 + 当前时间红线。
 */
@Composable
fun WeekGrid(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    now: LocalDateTime,
    onCourseClick: (CourseEntity) -> Unit,
    onEmptyTap: (day: Int, section: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 弹起高亮中的课程（详情打开期间；面板一开始滑出即恢复，见 CourseDetailSheet） */
    selectedCourseId: Long? = null,
) {
    val colors = LocalCourseColors.current
    val sections = ui.sections
    if (sections.isEmpty()) return
    val sectionCount = sections.size
    val gridHeight = GridDims.SECTION_HEIGHT * sectionCount
    val today = now.toLocalDate()

    Column(modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                TimeAxis(ui.sections)
                visibleDays.forEach { day ->
                    DayColumn(
                        day = day,
                        ui = ui,
                        week = week,
                        today = today,
                        sections = sections,
                        onCourseClick = onCourseClick,
                        onEmptyTap = onEmptyTap,
                        selectedCourseId = selectedCourseId,
                    )
                }
            }
            CurrentTimeLine(ui, week, now)
        }
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun TimeAxis(sections: List<SectionTimeEntity>) {
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val sectionH = with(density) { GridDims.SECTION_HEIGHT.toPx() }
    Column(
        Modifier
            .width(GridDims.TIME_AXIS_WIDTH)
            .drawBehind {
                drawSectionLines(sections, sectionH, colors)
            }
    ) {
        val periods = sections.groupBy { it.periodNo }.toSortedMap()
        periods.forEach { (period, secs) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(GridDims.SECTION_HEIGHT * secs.size),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "$period",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = secs.firstOrNull()?.startTime ?: "",
                    color = colors.textMuted,
                    fontSize = 9.sp,
                )
                Text(
                    text = secs.lastOrNull()?.endTime ?: "",
                    color = colors.textMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun RowScope.DayColumn(
    day: Int,
    ui: ScheduleUiState,
    week: Int,
    today: java.time.LocalDate,
    sections: List<SectionTimeEntity>,
    onCourseClick: (CourseEntity) -> Unit,
    onEmptyTap: (day: Int, section: Int) -> Unit,
    selectedCourseId: Long?,
) {
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val sectionH = with(density) { GridDims.SECTION_HEIGHT.toPx() }
    val sectionCount = sections.size
    val isToday = ui.dateOf(week, day) == today
    val courses = ui.coursesAt(week, day)

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .height(GridDims.SECTION_HEIGHT * sectionCount)
            .background(if (isToday) colors.accent.copy(alpha = 0.05f) else Color.Transparent)
            .drawBehind {
                drawSectionLines(sections, sectionH, colors)
                // 列分隔线
                drawLine(
                    colors.border,
                    androidx.compose.ui.geometry.Offset(size.width - 0.5f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width - 0.5f, size.height),
                    strokeWidth = 1f,
                )
            }
            .pointerInput(week, day, sections) {
                detectTapGestures { offset ->
                    val section = ((offset.y / sectionH).toInt() + 1).coerceIn(1, sectionCount)
                    onEmptyTap(day, section)
                }
            }
    ) {
        courses.forEach { course ->
            val startIdx = course.startSection.coerceIn(1, sectionCount)
            val endIdx = course.endSection.coerceIn(startIdx, sectionCount)
            val span = endIdx - startIdx + 1
            val startTime = sections.getOrNull(startIdx - 1)?.startTime.orEmpty()
            CourseBlock(
                course = course,
                preset = LocalCoursePreset.current,
                colors = colors,
                isDark = LocalIsDark.current,
                startTime = startTime,
                selected = course.id == selectedCourseId,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = GridDims.SECTION_HEIGHT * (startIdx - 1))
                    .height(GridDims.SECTION_HEIGHT * span - 3.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp),
                onClick = { onCourseClick(course) },
            )
        }
    }
}

/** 小节分隔线：普通节次 hairline，大节/分场线加重 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSectionLines(
    sections: List<SectionTimeEntity>,
    sectionH: Float,
    colors: com.zhijian.schedule.core.theme.CourseColors,
) {
    for (i in 1 until sections.size) {
        val prev = sections[i - 1]
        val cur = sections[i]
        val y = i * sectionH
        val (color, width) = when {
            prev.session != cur.session -> colors.accent.copy(alpha = 0.35f) to 2f
            prev.periodNo != cur.periodNo -> colors.border to 1.5f
            else -> colors.border.copy(alpha = 0.45f) to 1f
        }
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = width)
    }
}

/** 当前时间红线（仅本周可见时）：呼吸脉冲 + 端点圆点，让「现在」是活的 */
@Composable
private fun CurrentTimeLine(ui: ScheduleUiState, week: Int, now: LocalDateTime) {
    val sections = ui.sections
    if (ui.currentWeek != week || sections.isEmpty()) return
    val todayDay = TimeUtils.dayOfWeek(now.toLocalDate())
    val startDate = ui.startDate
    val date = TimeUtils.dateOfWeekDay(startDate, week, todayDay)
    if (date != now.toLocalDate()) return

    val pos = sectionPosition(sections, now.toLocalTime()) ?: return
    val density = LocalDensity.current
    val yDp = with(density) { (pos * GridDims.SECTION_HEIGHT.toPx()).toDp() }
    val nowColor = Color(0xFFE5484D)

    val breath = rememberInfiniteTransition(label = "nowLine")
    val pulse by breath.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "nowLinePulse",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = GridDims.TIME_AXIS_WIDTH)
            .offset(y = yDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(nowColor.copy(alpha = pulse))
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(nowColor.copy(alpha = pulse * 0.8f))
        )
    }
}

/** 当前时间在网格中的 y 位置（单位：小节高度倍数），不在上课时间内返回 null */
private fun sectionPosition(sections: List<SectionTimeEntity>, now: LocalTime): Float? {
    sections.forEachIndexed { i, s ->
        val st = TimeUtils.parseTime(s.startTime) ?: return@forEachIndexed
        val et = TimeUtils.parseTime(s.endTime) ?: return@forEachIndexed
        if (!now.isBefore(st) && !now.isAfter(et)) {
            val total = java.time.Duration.between(st, et).seconds.coerceAtLeast(1)
            val passed = java.time.Duration.between(st, now).seconds
            return i + passed.toFloat() / total
        }
    }
    return null
}
