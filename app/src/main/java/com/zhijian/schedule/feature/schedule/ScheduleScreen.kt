package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.components.WeekGrid
import com.zhijian.schedule.feature.schedule.components.WeekHeader
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(
    onAddCourse: (day: Int?, section: Int?) -> Unit = { _, _ -> },
    onEditCourse: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var dayDetail by remember { mutableStateOf<LocalDate?>(null) }
    var clock by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = LocalDateTime.now()
        }
    }

    val visibleDays = if (ui.settings.showWeekend) (1..7).toList() else (1..5).toList()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { ui.totalWeeks },
    )

    // 课表加载完成、或开学日期/总周数被修改后，重新定位到当前周
    LaunchedEffect(ui.timetable?.id, ui.timetable?.startDate, ui.timetable?.totalWeeks) {
        val target = ((ui.currentWeek ?: 1) - 1).coerceIn(0, ui.totalWeeks - 1)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.selectWeek(page + 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScheduleTopBar(
            ui = ui,
            onSelectWeek = { week -> scope.launch { pagerState.animateScrollToPage(week - 1) } },
            onOpenSettings = onOpenSettings,
        )
        val emptyState = ui.timetable != null && ui.courses.isEmpty()
        WeekHeader(
            ui = ui,
            week = ui.selectedWeek,
            visibleDays = visibleDays,
            today = clock.toLocalDate(),
            alignToTimeAxis = !emptyState,
            onDayClick = { day -> dayDetail = ui.dateOf(ui.selectedWeek, day) },
        )

        if (emptyState) {
            StaggerIn(index = 0, modifier = Modifier.fillMaxSize()) {
                EmptyScheduleCard(
                    onAdd = { onAddCourse(null, null) },
                    onImport = onOpenImport,
                )
            }
        } else {
            Box(Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    WeekGrid(
                        ui = ui,
                        week = page + 1,
                        visibleDays = visibleDays,
                        now = clock,
                        onCourseClick = { selectedCourse = it },
                        onEmptyTap = { day, section -> onAddCourse(day, section) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    val fabSource = remember { MutableInteractionSource() }
    var fabArrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fabArrived = true }
    val fabScale by animateFloatAsState(
        targetValue = if (fabArrived) 1f else 0f,
        animationSpec = Motion.PopSpring,
        label = "fabEntrance",
    )
    Box(Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { onAddCourse(null, null) },
            interactionSource = fabSource,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .pressScale(fabSource, pressedScale = 0.9f)
                .graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                    alpha = fabScale.coerceIn(0f, 1f)
                },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加课程")
        }
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

    dayDetail?.let { date ->
        DayDetailSheet(
            ui = ui,
            date = date,
            now = clock,
            onDateChange = { dayDetail = it },
            onDismiss = { dayDetail = null },
        )
    }
}

@Composable
private fun ScheduleTopBar(
    ui: ScheduleUiState,
    onSelectWeek: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalCourseColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = ui.selectedWeek,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInVertically(
                    animationSpec = tween(Motion.DurMedium, easing = Motion.EaseOut),
                ) { dir * it / 2 } + fadeIn(tween(Motion.DurMedium, easing = Motion.EaseOut))) togetherWith
                    (slideOutVertically(
                        animationSpec = tween(Motion.DurFast),
                    ) { -dir * it / 2 } + fadeOut(tween(Motion.DurFast)))
            },
            modifier = Modifier.weight(1f),
            label = "weekTitle",
        ) { week ->
            val start = ui.dateOf(week, 1)
            val end = ui.dateOf(week, 7)
            val subtitle = when {
                ui.currentWeek == null && ui.today.isBefore(ui.startDate) -> "第 $week 周 · 未开学"
                ui.currentWeek == null -> "第 $week 周 · 学期已结束"
                week == ui.currentWeek -> "本周 · 第 $week 周"
                else -> "第 $week 周 · 非本周"
            }
            Column {
                Text(
                    text = "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text("${ui.selectedWeek} 周", color = colors.text)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.textMuted)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (ui.currentWeek != null && ui.selectedWeek != ui.currentWeek) {
                    DropdownMenuItem(
                        text = { Text("回到本周", color = colors.accent) },
                        onClick = {
                            menuExpanded = false
                            onSelectWeek(ui.currentWeek)
                        },
                    )
                }
                (1..ui.totalWeeks).forEach { w ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (w == ui.currentWeek) "第 $w 周（本周）" else "第 $w 周",
                                color = if (w == ui.selectedWeek) colors.accent else colors.text,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSelectWeek(w)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = colors.textMuted)
        }
    }
}

@Composable
private fun EmptyScheduleCard(onAdd: () -> Unit, onImport: () -> Unit) {
    val colors = LocalCourseColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "还没有课程",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "手动添加，或从教务 / Excel / CSV 一键导入",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            Spacer(Modifier.height(18.dp))
            Row {
                val addSource = remember { MutableInteractionSource() }
                Button(
                    onClick = onAdd,
                    interactionSource = addSource,
                    modifier = Modifier.pressScale(addSource, pressedScale = 0.96f),
                ) { Text("添加课程") }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onImport) { Text("导入课表") }
            }
        }
    }
}
