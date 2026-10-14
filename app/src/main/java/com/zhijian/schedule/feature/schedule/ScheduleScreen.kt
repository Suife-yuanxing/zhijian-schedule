package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val haptics = LocalHapticFeedback.current
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    // 面板目标可见性：关闭动作一开始即置回 false，让课块高亮与面板滑出并行恢复
    var detailSheetVisible by remember { mutableStateOf(false) }
    // 周选择面板：shown 控制面板存在，open 跟随目标可见性（驱动标题箭头翻转）
    var weekPickerShown by remember { mutableStateOf(false) }
    var weekPickerOpen by remember { mutableStateOf(false) }
    var dayDetail by remember { mutableStateOf<LocalDate?>(null) }
    var clock by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = LocalDateTime.now()
        }
    }

    // 调休补班周：即使关着「显示周末」也强制显示周末列（补班日在该周）
    val visibleDays = if (ui.settings.showWeekend || ui.weekHasMakeupWorkday(ui.selectedWeek)) {
        (1..7).toList()
    } else {
        (1..5).toList()
    }
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
            pickerOpen = weekPickerOpen,
            onOpenWeekPicker = {
                weekPickerShown = true
                weekPickerOpen = true
            },
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
                        onCourseClick = { course ->
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            selectedCourse = course
                            detailSheetVisible = true
                        },
                        onEmptyTap = { day, section -> onAddCourse(day, section) },
                        modifier = Modifier.fillMaxSize(),
                        selectedCourseId = selectedCourse?.id?.takeIf { detailSheetVisible },
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

    dayDetail?.let { date ->
        DayDetailSheet(
            ui = ui,
            date = date,
            now = clock,
            onDateChange = { dayDetail = it },
            onDismiss = { dayDetail = null },
        )
    }

    if (weekPickerShown) {
        WeekPickerSheet(
            ui = ui,
            onSelect = { week -> scope.launch { pagerState.animateScrollToPage(week - 1) } },
            onTargetVisibilityChange = { weekPickerOpen = it },
            onDismiss = { weekPickerShown = false },
        )
    }
}

@Composable
private fun ScheduleTopBar(
    ui: ScheduleUiState,
    pickerOpen: Boolean,
    onOpenWeekPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val titleSource = remember { MutableInteractionSource() }
    // 面板开合时箭头翻转（弹出带一点过冲，呼应全局动效语言）
    val arrowAngle by animateFloatAsState(
        targetValue = if (pickerOpen) 180f else 0f,
        animationSpec = Motion.PopSpring,
        label = "weekArrow",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题即开关：点按「日期区间」展开周选择
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .pressScale(titleSource, pressedScale = 0.98f)
                .clickable(
                    interactionSource = titleSource,
                    indication = LocalIndication.current,
                    onClick = onOpenWeekPicker,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(20.dp)
                                .graphicsLayer { rotationZ = arrowAngle },
                        )
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
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
