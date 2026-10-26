package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate

/**
 * 点击表头某天后弹出的「日详情」：查看该日课程，可逐日前后翻、
 * 或经该星期几的 1~totalWeeks 周日期速选条直达过去/未来任意一周。
 *
 * 翻日动效：标题与课程列表随方向横滑切换（翻页感），日期速选条保持稳定，
 * 方便连续快速点选；翻日只保留位移动效，不再额外震动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    ui: ScheduleUiState,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCourseColors.current
    // 面板自己取时钟：宿主页面因此不必在页面级解包分钟。面板只在打开时组合，
    // 那句 1 次/分的重组就只落在这张短列表上，不会波及整张课表。
    val now = LocalClock.current.value
    val startDate = ui.startDate
    val endDate = startDate.plusDays(ui.totalWeeks * 7L - 1)
    val today = now.toLocalDate()

    val flipTo: (LocalDate) -> Unit = { d ->
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
