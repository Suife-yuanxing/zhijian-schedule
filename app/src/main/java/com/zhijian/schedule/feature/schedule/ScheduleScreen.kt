package com.zhijian.schedule.feature.schedule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.feature.schedule.components.WeekGrid
import com.zhijian.schedule.feature.schedule.components.WeekHeader
import com.zhijian.schedule.feature.schedule.components.rememberGridDensity
import com.zhijian.schedule.feature.schedule.components.rememberGridMetrics
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ScheduleScreen(
    onAddCourse: (day: Int?, section: Int?) -> Unit = { _, _ -> },
    /** 加号单独一条：带上按钮自身的 root 坐标，供「从加号圆形扩散进编辑页」用 */
    onAddCourseFromFab: (fabBoundsInRoot: Rect) -> Unit = { onAddCourse(null, null) },
    onEditCourse: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    islandCollapse: IslandCollapseState? = null,
    scrollToTopTick: Int = 0,
    /**
     * 入场回合：每次进入课表由宿主自增（见 AppRoot 的 scheduleEntryEpoch）。
     *
     * 为什么不能只靠「组合重新创建」：navigation-compose 的转场按 entry id 复用内容槽，
     * 而 saveState/restoreState 恢复的 entry 还是原来那个 id。于是在上一段转场收尾前
     * 切回来时，这一页根本没离开组合，`remember` 不重跑、级联不重播——真机上就是
     * 「慢慢切有入场动画、快速切没有」。
     */
    entryEpoch: Int = 0,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    // 面板目标可见性：关闭动作一开始即置回 false，让课块高亮与面板滑出并行恢复
    var detailSheetVisible by remember { mutableStateOf(false) }
    // 周选择面板：shown 控制面板存在，open 跟随目标可见性（驱动标题箭头翻转）
    var weekPickerShown by remember { mutableStateOf(false) }
    var weekPickerOpen by remember { mutableStateOf(false) }
    var dayDetail by remember { mutableStateOf<LocalDate?>(null) }
    // 「现在」来自 AppRoot 的全局整分时钟：不再自己起 30s 循环（那条会在相位上和
    // 今日页错开，同一屏两处时间对不上）。
    //
    // 这里**只拿到 State，不解包**：一旦在页面级写 clock.value，每分钟整页（含 pager
    // 预组合的邻页、几十个课块）就要跟着重组一次。订阅点全部下沉到真正需要分钟的叶子——
    // 表头只订阅「今天」，课块的「剩 X 分」只在今天那一列订阅，现在线自己订阅。
    // 这一页真正的常驻重绘不是时钟，是 NowMarker 的呼吸灯，见 WeekGrid。
    val clock = LocalClock.current
    // 「今天」：跨零点才变，表头与课块层共用这一份
    val today = remember(clock) { derivedStateOf { clock.value.toLocalDate() } }

    // 调休补班周：即使关着「显示周末」也强制显示周末列（补班日在该周）
    val visibleDays =
        if (ui.settings.showWeekend || ui.weekHasMakeupWorkday(ui.selectedWeek)) {
            (1..7).toList()
        } else {
            (1..5).toList()
        }
    val scope = rememberCoroutineScope()
    val pagerState =
        rememberPagerState(
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
        // 网格区是密集数据面：只把 fontScale 压到 1.5 倍上限（dp 不受影响），
        // 否则 5 列每列 63dp 宽，字体放到 2 倍就成一字一行了
        val gridDensity = rememberGridDensity()
        CompositionLocalProvider(LocalDensity provides gridDensity) {
            // 表头与网格共用同一份 metrics：字体放大后时间轴宽度必须对得上
            val gridMetrics = rememberGridMetrics()
            WeekHeader(
                ui = ui,
                week = ui.selectedWeek,
                visibleDays = visibleDays,
                today = today,
                alignToTimeAxis = !emptyState,
                onDayClick = { day ->
                    dayDetail = ui.dateOf(ui.selectedWeek, day)
                },
                metrics = gridMetrics,
            )

            if (emptyState) {
                // key(entryEpoch)：空状态卡片也按「回合」重播，和课块同一拍
                key(entryEpoch) {
                    StaggerIn(index = 0, modifier = Modifier.fillMaxSize()) {
                        EmptyScheduleCard(
                            onAdd = { onAddCourse(null, null) },
                            onImport = onOpenImport,
                        )
                    }
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
                            clock = clock,
                            onCourseClick = { course ->
                                selectedCourse = course
                                detailSheetVisible = true
                            },
                            onEmptyTap = { day, section ->
                                onAddCourse(day, section)
                            },
                            modifier = Modifier.fillMaxSize(),
                            selectedCourseId = selectedCourse?.id?.takeIf { detailSheetVisible },
                            entranceEpoch = entryEpoch,
                            islandCollapse = islandCollapse,
                            // 只有落定的那一页才算浮岛的持有者：beyondViewportPageCount=1 时邻页是
                            // 提前组合的，而每周 grid 各自 remember 一份新的 ScrollState（初始为 0），
                            // 不区分活跃页的话，浮岛收起后一翻周就会被邻页当成「回到顶部」顶开。
                            islandActive = page == pagerState.settledPage,
                            // 翻周动画期间冻结：横向 pager 与纵向 scroll 会在边界抢同一次手势
                            frozen = { pagerState.isScrollInProgress },
                            scrollToTopTick = scrollToTopTick,
                        )
                    }
                }
            }
        }
    }

    ScheduleFab(entryEpoch = entryEpoch, onAddCourseFromFab = onAddCourseFromFab)
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
