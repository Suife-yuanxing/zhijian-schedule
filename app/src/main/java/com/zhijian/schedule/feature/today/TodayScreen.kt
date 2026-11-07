package com.zhijian.schedule.feature.today
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.IslandScrollToTop
import com.zhijian.schedule.core.ui.island.islandScrollObserver
import com.zhijian.schedule.feature.schedule.CourseDetailSheet
import com.zhijian.schedule.navigation.IslandContentPadding
import java.time.LocalDateTime

@Composable
fun TodayScreen(
    onEditCourse: (Long, SeedShape?) -> Unit = { _, _ -> },
    onOpenExams: () -> Unit = {},
    islandCollapse: IslandCollapseState? = null,
    scrollToTopTick: Int = 0,
    /**
     * 是否播放整页的分段入场。
     *
     * tab 切页已经是一整屏横滑；若每次切回今日再叠一层 0~708ms 的 fade + 上浮，
     * 页面在滑动过程里是半透明的（内容一边滑一边长出来），而"看起来快好了"的那
     * 200ms 正好是三段动画同时收尾的窗口——这时再切走，图层连同页面一起被销毁重建，
     * 观感就是闪一下。三个根页现在是同一条规矩（见 AppNavHostState）：切 tab 只做位移，
     * 入场只在本次进程第一次进入时播。
     */
    playEntrance: Boolean = true,
    /** 首次入场已经开始：宿主据此记住「已经播过」，下次切回不再重播 */
    onEntranceShown: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // 冻结成本次组合的常量：回调置位后入参会变成 false，但这一轮的入场已经起播，不能中途改
    val entranceOnce = remember { playEntrance }
    LaunchedEffect(Unit) { if (entranceOnce) onEntranceShown() }
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    // 被点课卡的实测轮廓：点开面板那一刻定格，编辑跳转的圆形从这一卡长出来
    // （与课表 tab 同一条语言，见 docs/specs/2026-11-07-edit-reveal-design.md）
    var editSeed by remember { mutableStateOf<SeedShape?>(null) }
    // 面板目标可见性：关闭动作一开始即置回 false，让行高亮与面板滑出并行恢复
    var detailSheetVisible by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    // 复点「今日」回顶：由 AppRoot 的 tick 触发
    IslandScrollToTop(state = islandCollapse, scroll = scrollState, signal = scrollToTopTick)
    Column(
        Modifier
            .fillMaxSize()
            .islandScrollObserver(state = islandCollapse, scroll = scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        // 状态栏起始位移：静止时与原布局一致；滚动时内容从顶部磨砂檐下穿过
        Spacer(Modifier.statusBarsPadding().height(18.dp))
        TodayHeader(ui)

        Spacer(Modifier.height(18.dp))

        TodayContent(
            ui = ui,
            entranceOnce = entranceOnce,
            onOpenExams = onOpenExams,
            selectedCourseId = selectedCourse?.id?.takeIf { detailSheetVisible },
            onRowClick = { item, seed ->
                editSeed = seed
                selectedCourse = item.course
                detailSheetVisible = true
            },
        )
        // 恒定留白（同 WeekGrid）：不在组合期读收敛值，避免浮岛每帧动画都让整页重新测量。
        // navigationBarsPadding 把手势条高度补进让位，内容可以一直铺到屏幕底
        Spacer(Modifier.navigationBarsPadding().height(IslandContentPadding))
    }

    selectedCourse?.let { course ->
        val seed = editSeed
        CourseDetailSheet(
            course = course,
            onTargetVisibilityChange = { detailSheetVisible = it },
            onDismiss = {
                selectedCourse = null
                editSeed = null
            },
            onEdit = {
                // 面板整体退场由面板自己 hide，播完走 onDismiss 摘组合；这里只启动导航 + reveal
                onEditCourse(course.id, seed)
            },
            onDelete = {
                viewModel.deleteCourse(course.id)
                selectedCourse = null
                editSeed = null
            },
        )
    }
}

/**
 * 内容区：假期提示 / 空状态 / 正在上课与下一节 / 课程列表。
 *
 * 从 [TodayScreen] 拆出来不只是为了 detekt 的 100 行线：current/next 的派生订阅也
 * 落在这一段而不是页面级，上/下课边界一来，重组只落在内容区，不再牵动底部面板胶水。
 */
@Composable
private fun TodayContent(
    ui: TodayUiState,
    entranceOnce: Boolean,
    onOpenExams: () -> Unit,
    /** 详情打开期间应高亮的那一行（课程 id）；null 表示无 */
    selectedCourseId: Long?,
    onRowClick: (TodayCourseItem, SeedShape) -> Unit,
) {
    val clock = LocalClock.current
    val current = currentCourseOf(ui, clock)
    val next = nextCourseOf(ui, clock)

    ui.todayHoliday?.takeIf { it.isOffDay && ui.courses.isNotEmpty() }?.let { h ->
        StaggerIn(index = 0, skip = !entranceOnce) {
            HolidayNoticeCard(h)
        }
        Spacer(Modifier.height(14.dp))
    }

    when {
        ui.courses.isEmpty() -> EmptyTodaySection(ui, entranceOnce, clock, onOpenExams)
        else -> {
            current?.let { item ->
                StaggerIn(index = 0, skip = !entranceOnce) {
                    CurrentClassCard(item, clock, sweepOnce = entranceOnce)
                }
                Spacer(Modifier.height(14.dp))
            }
            next?.let { item ->
                StaggerIn(index = 1, skip = !entranceOnce) {
                    NextClassCard(item, clock)
                }
                Spacer(Modifier.height(18.dp))
            }
            ui.nextExam?.let { exam ->
                StaggerIn(index = 1, skip = !entranceOnce) {
                    ExamCountdownCard(exam, clock, onClick = onOpenExams)
                }
                Spacer(Modifier.height(14.dp))
            }
            TodayCourseList(
                ui = ui,
                clock = clock,
                current = current,
                entranceOnce = entranceOnce,
                selectedCourseId = selectedCourseId,
                onRowClick = onRowClick,
            )
        }
    }
}

/** 「正在上的课」：结果是课程对象，只有上/下课这种边界才会变（订阅策略见 TodayScreen 的时钟注释） */
@Composable
private fun currentCourseOf(
    ui: TodayUiState,
    clock: State<LocalDateTime>,
): TodayCourseItem? {
    val current by remember(ui.courses) {
        derivedStateOf {
            val now = clock.value.toLocalTime()
            ui.courses.firstOrNull { item ->
                val s = item.startTime
                val e = item.endTime
                s != null && e != null && !now.isBefore(s) && !now.isAfter(e)
            }
        }
    }
    return current
}

/** 「下一节课」：同 [currentCourseOf] 的订阅策略 */
@Composable
private fun nextCourseOf(
    ui: TodayUiState,
    clock: State<LocalDateTime>,
): TodayCourseItem? {
    val next by remember(ui.courses) {
        derivedStateOf {
            val now = clock.value.toLocalTime()
            ui.courses.firstOrNull { item -> item.startTime != null && now.isBefore(item.startTime) }
        }
    }
    return next
}

/** 空状态：没有课的一天，只留考试倒计时 */
@Composable
private fun EmptyTodaySection(
    ui: TodayUiState,
    entranceOnce: Boolean,
    clock: State<LocalDateTime>,
    onOpenExams: () -> Unit,
) {
    StaggerIn(index = 0, skip = !entranceOnce) { EmptyToday(ui) }
    Spacer(Modifier.height(14.dp))
    ui.nextExam?.let { exam ->
        StaggerIn(index = 1, skip = !entranceOnce) {
            ExamCountdownCard(exam, clock, onClick = onOpenExams)
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** 「今日课程」标题 + 按上午/下午/晚上分组的行列表 */
@Composable
private fun TodayCourseList(
    ui: TodayUiState,
    clock: State<LocalDateTime>,
    current: TodayCourseItem?,
    entranceOnce: Boolean,
    selectedCourseId: Long?,
    onRowClick: (TodayCourseItem, SeedShape) -> Unit,
) {
    val colors = LocalCourseColors.current
    val groups =
        DayPart.entries.mapNotNull { part ->
            val entries =
                ui.courses
                    .withIndex()
                    .filter { dayPartOf(it.value.startTime) == part }
            if (entries.isEmpty()) null else part to entries
        }
    // 与上方卡片共用一条 stagger 序列：标题从 2 起，往下逐行递增
    var staggerIndex = 2
    StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
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
        StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
            DayPartHeader(part, entries.size)
        }
        entries.forEach { (_, item) ->
            StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
                CourseRow(
                    item = item,
                    clock = clock,
                    isCurrent = item == current,
                    selected = item.course.id == selectedCourseId,
                    onClick = { seed -> onRowClick(item, seed) },
                )
            }
        }
    }
}
