package com.zhijian.schedule.feature.schedule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.feature.schedule.components.WeekGrid
import com.zhijian.schedule.feature.schedule.components.WeekHeader
import com.zhijian.schedule.feature.schedule.components.rememberGridDensity
import com.zhijian.schedule.feature.schedule.components.rememberGridMetrics
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 课表页上「会打开又关掉」的那几层：课程详情面板、周选择面板、某一天详情。
 *
 * 收成一个持有者而不是四个各自 remember 的布尔/可空值：它们的开合是**互斥语义**
 * （同一时刻只该有一层在），分散声明时这条约束只能靠读代码脑补；写在一起之后，
 * 「谁在开」是一个可以被读到的事实。
 */
private class ScheduleSheets {
    /** 被点开的课程（详情面板）；null = 没有面板 */
    var course by mutableStateOf<CourseEntity?>(null)

    /**
     * 面板**目标**可见性：关闭动作一开始就置回 false，
     * 让课块高亮恢复与面板滑出并行播，不必等面板走完。
     */
    var detailVisible by mutableStateOf(false)

    /** 周选择面板：shown 控制面板存在，open 跟随目标可见性（驱动标题箭头翻转） */
    var weekPickerShown by mutableStateOf(false)
    var weekPickerOpen by mutableStateOf(false)

    /** 某一天的详情（点表头日期） */
    var dayDetail by mutableStateOf<LocalDate?>(null)
}

@Composable
private fun rememberScheduleSheets(): ScheduleSheets {
    val sheets = remember { ScheduleSheets() }
    return sheets
}

/**
 * 空课表的一屏：引导卡片。
 *
 * 单独抽出来的理由不只是行数：它的入场闸门（[skipEntrance]）与课块级联是**同一拍**，
 * 两者必须由同一个 `entranceOnce` 决定，写在页面函数里时这条耦合是隐形的。
 */
@Composable
private fun EmptySchedulePane(
    skipEntrance: Boolean,
    onAdd: (SeedShape) -> Unit,
    onImport: () -> Unit,
) {
    // 空状态卡片与课块同一拍：只有本次进程第一次进入才播，之后首帧直接落位。
    // 旧实现用 key(entryEpoch) 强制重播——key 会把整棵子树销毁重建，比"重播一段
    // 淡入"重得多，而它换来的正是「切页像刷新」的观感。
    StaggerIn(
        index = 0,
        modifier = Modifier.fillMaxSize(),
        skip = skipEntrance,
    ) {
        EmptyScheduleCard(onAdd = onAdd, onImport = onImport)
    }
}

/**
 * 网格这一屏需要的全部输入：数据、滚动状态、入场闸门、回调。
 *
 * 打包的理由同 [BandScene]——pager 与 [WeekGrid] 之间那十几个参数没有一个是
 * 「这一屏独有的判定」，全是同一份课表状态的分发；每次加一个交互（例如这次的
 * 空课块原点）都要在两处签名上各添一项，漏传还不报错。
 */
private class GridScene(
    val ui: ScheduleUiState,
    val pagerState: androidx.compose.foundation.pager.PagerState,
    val visibleDays: List<Int>,
    val clock: androidx.compose.runtime.State<java.time.LocalDateTime>,
    val entranceOnce: Boolean,
    val islandCollapse: IslandCollapseState?,
    val scrollToTopTick: Int,
)

/** 周视图 pager：每一页是一周的 [WeekGrid] */
@Composable
private fun ColumnScope.WeekPager(
    scene: GridScene,
    selectedCourse: CourseEntity?,
    detailSheetVisible: Boolean,
    onCourseClick: (CourseEntity) -> Unit,
    onEmptyTap: (day: Int, section: Int, seed: SeedShape) -> Unit,
) {
    val pagerState = scene.pagerState
    Box(Modifier.weight(1f)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            WeekGrid(
                ui = scene.ui,
                week = page + 1,
                visibleDays = scene.visibleDays,
                clock = scene.clock,
                onCourseClick = onCourseClick,
                onEmptyTap = onEmptyTap,
                modifier = Modifier.fillMaxSize(),
                selectedCourseId = selectedCourse?.id?.takeIf { detailSheetVisible },
                playEntrance = scene.entranceOnce,
                islandCollapse = scene.islandCollapse,
                // 只有落定的那一页才算浮岛的持有者：beyondViewportPageCount=1 时邻页是
                // 提前组合的，而每周 grid 各自 remember 一份新的 ScrollState（初始为 0），
                // 不区分活跃页的话，浮岛收起后一翻周就会被邻页当成「回到顶部」顶开。
                islandActive = page == pagerState.settledPage,
                // 翻周动画期间冻结：横向 pager 与纵向 scroll 会在边界抢同一次手势
                frozen = { pagerState.isScrollInProgress },
                scrollToTopTick = scene.scrollToTopTick,
            )
        }
    }
}

/**
 * 周次与 pager 的双向同步：定位到当前周、翻页回写选中周。
 *
 * 从 [ScheduleScreen] 里抽出来的理由与动效无关——这两段副作用各带一整段历史注释
 * （为什么会覆盖用户手选的周、为什么不能挂在 anchor 的 key 上），
 * 留在页面函数里会让「页面长什么样」被「页面什么时候该重新定位」淹掉。
 */
@Composable
private fun rememberWeekSync(
    ui: ScheduleUiState,
    viewModel: ScheduleViewModel,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    // 定位到当前周：**只认锚点变化**（课表 id / 开学日期 / 总周数）。
    //
    // 旧实现直接把这个副作用挂在上面那三个 key 上，于是页面每次重新进入组合都会跑一次
    // ——切 tab 回来、从编辑页返回、Activity 重建都算——把用户手选的周次覆盖回「自然当前周」。
    // 那正是「根页面切换时刷新位置错误」：翻到第 10 周、切走再切回，位置变回第 9 周。
    // 现在用 anchor 记住「上次是按哪份课表定位的」，锚点没变就一个字节都不动，
    // 页码交给 pager 自己的 saveable 状态恢复（规则与单测见 WeekAnchorPolicy）。
    val anchor = WeekAnchorPolicy.anchorOf(ui.timetable?.id, ui.timetable?.startDate, ui.totalWeeks)
    LaunchedEffect(anchor) {
        // 锚点存在 ViewModel 里：页面重建（切页 / 返回 / Activity 重建）不会把它清零
        if (!viewModel.shouldRelocate(anchor)) return@LaunchedEffect
        val target = WeekAnchorPolicy.targetPage(ui.currentWeek, ui.totalWeeks)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.selectWeek(page + 1)
        }
    }
}

@Composable
fun ScheduleScreen(
    /**
     * 点空课块（或空状态卡片的「添加课程」）：带上被点控件的实测轮廓。
     *
     * [seed] 是轮廓而不是可空 null，是为了让「页面从哪里长出来」成为一个必答项——
     * 传 bounds = Rect.Zero 才是「这个地方没有可用的几何，直接导航」，一处也不会漏。
     */
    onAddCourse: (day: Int?, section: Int?, seed: SeedShape) -> Unit = { _, _, _ -> },
    /** 加号单独一条：带上方块自身的实测轮廓（含颜色副本），供「页面从方块轮廓长出来」用 */
    onAddCourseFromFab: (seed: SeedShape) -> Unit = { onAddCourse(null, null, it) },
    onEditCourse: (Long) -> Unit = {},
    onOpenImport: () -> Unit = {},
    /** 从课程编辑页返回时的复位信号：加号要把 × 转回 +，见 AppNavHostState.fabResetTick */
    fabResetTick: Int = 0,
    islandCollapse: IslandCollapseState? = null,
    scrollToTopTick: Int = 0,
    /**
     * 是否播放课块级联入场：只有冷启动首帧为 true（见 RootEntrancePolicy）。
     *
     * 为什么需要宿主闸门而不是只看组合生命周期：navigation-compose 的转场按 entry id 复用
     * 内容槽，而 saveState/restoreState 恢复的 entry 还是原来那个 id——在上一段转场收尾前
     * 切回来时，这一页根本没离开组合，级联不会自己重播；反过来，切走够久让页面真被销毁，
     * 切回时又会从 alpha=0 重来。同一个动作两种结果，取决于转场有没有收尾，这就是「偶发」。
     *
     * 为什么不每次进入都播：那会把所有课块 alpha 归零、按阅读顺序逐块长回来（整轮封顶
     * 700ms），而切页滑动只要约 230ms，读起来就是「这一页刷新了一遍、从左上角摊开」。
     */
    playEntrance: Boolean = false,
    /** 首帧入场已经开始：宿主据此关闸，之后切回（含二级页返回、Activity 重建）不再重播 */
    onEntranceShown: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // 冻结成本次组合的常量：首回合起播之后，宿主把闸门关掉会让入参变 false，
    // 但这一轮级联已经在跑，不能中途把 delay 改成 -1 把它按停（那会留下半透明的块）。
    val entranceOnce = remember { playEntrance }
    // 与今日页 / 我的页同一写法：入场起播就关闸，三个根页共用宿主那一份闸门
    LaunchedEffect(Unit) { if (entranceOnce) onEntranceShown() }
    val sheets = rememberScheduleSheets()
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

    rememberWeekSync(ui, viewModel, pagerState)

    Column(Modifier.fillMaxSize()) {
        ScheduleTopBar(
            ui = ui,
            pickerOpen = sheets.weekPickerOpen,
            onOpenWeekPicker = {
                sheets.weekPickerShown = true
                sheets.weekPickerOpen = true
            },
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
                    sheets.dayDetail = ui.dateOf(ui.selectedWeek, day)
                },
                metrics = gridMetrics,
            )

            if (emptyState) {
                EmptySchedulePane(
                    skipEntrance = !entranceOnce,
                    onAdd = { origin -> onAddCourse(null, null, origin) },
                    onImport = onOpenImport,
                )
            } else {
                WeekPager(
                    scene =
                        GridScene(
                            ui = ui,
                            pagerState = pagerState,
                            visibleDays = visibleDays,
                            clock = clock,
                            entranceOnce = entranceOnce,
                            islandCollapse = islandCollapse,
                            scrollToTopTick = scrollToTopTick,
                        ),
                    selectedCourse = sheets.course,
                    detailSheetVisible = sheets.detailVisible,
                    onCourseClick = { course ->
                        sheets.course = course
                        sheets.detailVisible = true
                    },
                    onEmptyTap = { day, section, origin -> onAddCourse(day, section, origin) },
                )
            }
        }
    }

    ScheduleFab(
        playEntrance = entranceOnce,
        onAddCourseFromFab = onAddCourseFromFab,
        resetTick = fabResetTick,
    )
    ScheduleSheetsHost(
        sheets = sheets,
        ui = ui,
        onEditCourse = onEditCourse,
        onDeleteCourse = { viewModel.deleteCourse(it) },
        onSelectWeek = { week -> scope.launch { pagerState.animateScrollToPage(week - 1) } },
    )
}

/**
 * 三层浮层的挂载点：课程详情 / 某日详情 / 周选择。
 *
 * 抽出来是因为 [ScheduleScreen] 的职责只是「排这一屏」，而这三次 let/if 属于
 * 「谁盖在谁上面」的浮层编排——混在页面里时，页面函数一半在说布局、
 * 一半在说浮层生命周期，两件事都读不完整。
 */
@Composable
private fun ScheduleSheetsHost(
    sheets: ScheduleSheets,
    ui: ScheduleUiState,
    onEditCourse: (Long) -> Unit,
    onDeleteCourse: (Long) -> Unit,
    onSelectWeek: (Int) -> Unit,
) {
    sheets.course?.let { course ->
        CourseDetailSheet(
            course = course,
            onTargetVisibilityChange = { sheets.detailVisible = it },
            onDismiss = { sheets.course = null },
            onEdit = {
                sheets.course = null
                onEditCourse(course.id)
            },
            onDelete = {
                onDeleteCourse(course.id)
                sheets.course = null
            },
        )
    }

    sheets.dayDetail?.let { date ->
        DayDetailSheet(
            ui = ui,
            date = date,
            onDateChange = { sheets.dayDetail = it },
            onDismiss = { sheets.dayDetail = null },
        )
    }

    if (sheets.weekPickerShown) {
        WeekPickerSheet(
            ui = ui,
            onSelect = onSelectWeek,
            onTargetVisibilityChange = { sheets.weekPickerOpen = it },
            onDismiss = { sheets.weekPickerShown = false },
        )
    }
}
