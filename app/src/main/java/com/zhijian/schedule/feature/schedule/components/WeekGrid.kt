package com.zhijian.schedule.feature.schedule.components
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalGridStyle
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.IslandScrollToTop
import com.zhijian.schedule.core.ui.island.islandScrollObserver
import com.zhijian.schedule.feature.schedule.ScheduleUiState
import com.zhijian.schedule.navigation.IslandContentPadding
import java.time.LocalDateTime

/**
 * 周视图网格。
 *
 * 纵向结构由 [GridLayout] 决定：上午 / 下午 / 晚上三段标题 + 段间留白，
 * 段内只在「大节」之间留一条 hairline（色块制风格下一条线都不画）。
 * 课块与「现在」标记都画在同一层绝对坐标覆盖层上，因此可以跨时段、跨空隙连续存在。
 */
@Composable
fun WeekGrid(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    /**
     * 全局整分时钟，**不在本函数体里解包**。
     *
     * 这里传 State 而不是 LocalDateTime：课块层的「剩 X 分」和「现在线」各需要分钟，
     * 但网格背景、时段标题、天的底纹都不需要。页面级解包等于每分钟把整张网格（含 pager
     * 预组合的邻页）连同几十个课块一起重组一次；传下去之后只有真正读 .value 的叶子失效。
     */
    clock: State<LocalDateTime>,
    onCourseClick: (CourseEntity) -> Unit,
    onEmptyTap: (day: Int, section: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 弹起高亮中的课程（详情打开期间；面板一开始滑出即恢复，见 CourseDetailSheet） */
    selectedCourseId: Long? = null,
    /**
     * 是否播放课块级联入场：只有冷启动首帧为 true（见 RootEntrancePolicy）。
     *
     * 之前每次进入都重播：转场复用内容槽时页面不会离开组合，级联也就不会自己重播，
     * 于是用「回合号换 key」强推；代价是每切一次 tab 整屏课块都从 alpha=0 长一遍。
     */
    playEntrance: Boolean = false,
    /** 浮岛收敛观察器：只观察不消费，页面照常滚动 */
    islandCollapse: IslandCollapseState? = null,
    /**
     * 这一页此刻是否「拥有」浮岛（pager 只有 settle 的那一页算）。
     *
     * 非活跃页既不贡献收敛量，也不算「滚回顶部」：beyondViewportPageCount=1 时
     * 邻页是提前组合的，且每周 grid 的 ScrollState 是各自 remember 出来的新实例，
     * 初始位置恒为 0。若不区分，浮岛收起后一翻周就会被邻页当成「回到顶部」顶开。
     */
    islandActive: Boolean = true,
    /**
     * 横向翻周进行中：期间冻结收敛，避免与 pager 横滑抢同一次手势。
     *
     * 传 lambda 而不是 Boolean：Boolean 会在组合期读 pagerState.isScrollInProgress，
     * 手指每起落一次就重组整个课表页（含 3 个 grid）。lambda 只会在
     * NestedScrollConnection 的回调里被读，不订阅状态。
     */
    frozen: () -> Boolean = { false },
    /** 复点当前 tab 的回顶信号：自增即触发滚回本周顶部 */
    scrollToTopTick: Int = 0,
) {
    val sections = ui.sections
    if (sections.isEmpty()) return
    val metrics = rememberGridMetrics()
    val layout = remember(sections, metrics) { GridLayout(sections, metrics) }
    val style = LocalGridStyle.current
    val colors = LocalCourseColors.current
    val scroll = rememberScrollState()
    // 复点当前 tab 回顶：滚回「上午」段头，同时把浮岛展开（只由落定的那一页响应）
    IslandScrollToTop(
        state = islandCollapse,
        scroll = scroll,
        signal = scrollToTopTick,
        active = islandActive,
    )
    // 只在真的滚起来之后才盖渐隐：静止时盖住的是「上午」标题，会把它洗淡
    val scrolled by remember { derivedStateOf { scroll.value > 4 } }
    val fade by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(Motion.DUR_FAST),
        label = "scrollFade",
    )

    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .islandScrollObserver(
                    state = islandCollapse,
                    scroll = scroll,
                    active = islandActive,
                    frozen = frozen,
                ).verticalScroll(scroll),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dayWidth = (maxWidth - metrics.timeAxisWidth) / visibleDays.size

                // 背景层：时段标题 + 时间轴 + 天列
                Column(Modifier.fillMaxWidth()) {
                    layout.bands.forEachIndexed { bandIndex, band ->
                        BandHeader(band, style, metrics)
                        BandBody(
                            band = band,
                            layout = layout,
                            ui = ui,
                            week = week,
                            visibleDays = visibleDays,
                            style = style,
                            metrics = metrics,
                            onEmptyTap = onEmptyTap,
                        )
                        if (bandIndex != layout.bands.lastIndex) {
                            Spacer(Modifier.height(metrics.bandGap))
                        }
                    }
                }

                // 课块层
                Box(Modifier.fillMaxWidth().height(layout.totalHeight)) {
                    CourseOverlay(
                        ui = ui,
                        week = week,
                        visibleDays = visibleDays,
                        layout = layout,
                        dayWidth = dayWidth,
                        clock = clock,
                        playEntrance = playEntrance,
                        selectedCourseId = selectedCourseId,
                        metrics = metrics,
                        onCourseClick = onCourseClick,
                    )
                }

                // 现在层
                Box(Modifier.fillMaxWidth().height(layout.totalHeight)) {
                    NowMarker(ui, week, clock, layout, visibleDays, metrics)
                }
            }
            // 恒定留白：跟收敛进度插值的话，浮岛每动一帧都会改内容高度 → 整页重新测量/布局，
            // 滚动中的显隐还会顺带改 maxScroll 让内容跳一下。18dp 的差别落在浮岛底下，肉眼看不到。
            // navigationBarsPadding 把手势条高度补进让位：网格铺满全屏，末行收在浮岛上方
            Spacer(Modifier.navigationBarsPadding().height(IslandContentPadding))
        }
        // 表头下方 22dp 渐隐：课块滚进来时是淡出，而不是被硬切一刀
        if (fade > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = fade }
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.bg, colors.bg.copy(alpha = 0f)),
                        ),
                    ),
            )
        }
    }
}
