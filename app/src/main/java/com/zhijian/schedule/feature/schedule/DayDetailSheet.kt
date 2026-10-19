package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 点击表头某天后弹出的「日详情」：查看该日课程，可逐日前后翻、
 * 或经该星期几的 1~totalWeeks 周日期速选条直达过去/未来任意一周。
 *
 * 翻日动效：标题与课程列表随方向横滑切换（翻页感），日期速选条保持稳定，
 * 方便连续快速点选；翻日伴随轻触觉反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    ui: ScheduleUiState,
    date: LocalDate,
    now: LocalDateTime,
    onDateChange: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val startDate = ui.startDate
    val endDate = startDate.plusDays(ui.totalWeeks * 7L - 1)
    val today = now.toLocalDate()
    val haptics = LocalHapticFeedback.current

    val flipTo: (LocalDate) -> Unit = { d ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onDateChange(d)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 方向感知翻页：向未来翻从右侧滑入，向过去翻从左侧滑入
    val flipSpec: AnimatedContentTransitionScope<LocalDate>.() -> ContentTransform = {
        val dir = if (targetState > initialState) 1 else -1
        (slideInHorizontally(animationSpec = Motion.SlideSpring) { dir * it / 3 } +
            fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut))) togetherWith
            (slideOutHorizontally(animationSpec = Motion.SlideSpring) { -dir * it / 3 } +
                fadeOut(tween(durationMillis = Motion.DurFast)))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            AnimatedContent(
                targetState = date,
                transitionSpec = flipSpec,
                label = "dayHeader",
            ) { d ->
                val dayOfWeek = TimeUtils.dayOfWeek(d)
                val week = TimeUtils.weekOfSemester(startDate, ui.totalWeeks, d)
                val courses = week?.let { ui.coursesAt(it, dayOfWeek) }.orEmpty()
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${TimeUtils.dayName(dayOfWeek)} · 第 ${week ?: "—"} 周",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (d == today) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "今天",
                                color = colors.onAccent,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.accent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        ui.holidays[d.toString()]?.let { h ->
                            Spacer(Modifier.width(8.dp))
                            val tint = if (h.isOffDay) HolidayColors.REST else HolidayColors.WORK
                            Text(
                                text = "${h.name} · ${if (h.isOffDay) "休" else "班"}",
                                color = tint,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tint.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("${d.monthValue}月${d.dayOfMonth}日 · ")
                            append(if (courses.isEmpty()) "没有课" else "共 ${courses.size} 节")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TapTextButton(
                    onClick = { flipTo(date.minusDays(1)) },
                    enabled = date.isAfter(startDate),
                ) { Text("‹ 前一天") }
                Spacer(Modifier.weight(1f))
                TapTextButton(
                    onClick = { flipTo(date.plusDays(1)) },
                    enabled = date.isBefore(endDate),
                ) { Text("后一天 ›") }
            }

            val dayOfWeek = TimeUtils.dayOfWeek(date)
            val stripDates = remember(startDate, ui.totalWeeks, dayOfWeek) {
                (1..ui.totalWeeks).map { w -> TimeUtils.dateOfWeekDay(startDate, w, dayOfWeek) }
            }
            val selectedIdx = stripDates.indexOf(date)
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (selectedIdx - 2).coerceAtLeast(0),
            )
            LaunchedEffect(selectedIdx) {
                if (selectedIdx >= 0) {
                    listState.animateScrollToItem((selectedIdx - 2).coerceAtLeast(0))
                }
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(stripDates) { idx, d ->
                    DayPill(
                        date = d,
                        selected = d == date,
                        isCurrentWeek = ui.currentWeek == idx + 1,
                        past = d.isBefore(today),
                        onClick = { flipTo(d) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = date,
                transitionSpec = flipSpec,
                label = "dayCourses",
            ) { d ->
                val dDay = TimeUtils.dayOfWeek(d)
                val dWeek = TimeUtils.weekOfSemester(startDate, ui.totalWeeks, d)
                val dCourses = dWeek?.let { ui.coursesAt(it, dDay) }.orEmpty()
                Column {
                    if (dCourses.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("这天没有课", color = colors.textMuted, fontSize = 14.sp)
                        }
                    } else {
                        dCourses.forEach { course ->
                            DayCourseRow(
                                course = course,
                                sections = ui.sections,
                                date = d,
                                now = now,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 某星期几的周次速选 pill：选中弹跳放大 + 底色/描边/文字色渐变过渡 */
@Composable
private fun DayPill(
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
private fun DayCourseRow(
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
