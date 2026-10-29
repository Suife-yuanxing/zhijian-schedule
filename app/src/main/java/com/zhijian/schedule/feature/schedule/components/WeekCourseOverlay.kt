package com.zhijian.schedule.feature.schedule.components
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.ScheduleUiState
import java.time.Duration
import java.time.LocalDateTime

/**
 * 逐个入场的节拍：每个课块比上一个晚 70ms 起播，顺序是**阅读顺序**——
 * 先按起始节次从上到下，同一节里再按周一→周日（序列见 [CourseOverlay]）。
 *
 * 70ms ≈ 4 帧：紧接着上一个起播，又不至于糊成一片；配合收紧后的 [Motion.CascadeEnter]
 * （约 220ms 落位），任一时刻只有两三个块在动，读起来就是「一个一个落下来」。
 *
 * 整轮封顶 700ms：块多时按个数压缩节拍（7 天排满 20 个块 → 约 36ms/个）。
 * 不封顶的话满周入场要 1.4s，比切页滑动（约 230ms）长出一个数量级，就成了拖尾。
 */
private const val ENTER_STEP_MS = 70L
private const val ENTER_TOTAL_CAP_MS = 700L

/** 单个块的入场延迟 = 序号 × 节拍；节拍随块数自适应，保证整轮不超过上限 */
private fun entranceStepMs(count: Int): Long =
    if (count <= 1) 0L else minOf(ENTER_STEP_MS, ENTER_TOTAL_CAP_MS / (count - 1))

/** 所有课块：按绝对坐标铺在网格之上，跨时段也不会被裁 */
@Composable
internal fun CourseOverlay(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    layout: GridLayout,
    dayWidth: Dp,
    clock: State<LocalDateTime>,
    entranceEpoch: Int,
    selectedCourseId: Long?,
    metrics: GridMetrics,
    onCourseClick: (CourseEntity) -> Unit,
) {
    // 「今天」一天只换一次：派生值只服务「哪一列是今天」，整层不跟分钟走
    val today = remember(clock) { derivedStateOf { clock.value.toLocalDate() } }
    val todayDate = today.value

    // 入场序列：阅读顺序（先上后下，同一节内周一→周日），逐块延迟 = 序号 × 节拍。
    // 旧实现是「每列 +26ms、每行 +30ms」——延迟由坐标算出来，同一行/同一列的块几乎同时
    // 起播，一屏十来个块全挤在 340ms 里起完，读起来是一整片涌上来，不是逐个。
    val cells =
        visibleDays
            .flatMapIndexed { colIndex, day -> ui.coursesAt(week, day).map { Triple(colIndex, day, it) } }
            .sortedWith(compareBy({ it.third.startSection }, { it.first }))
    val step = entranceStepMs(cells.size)

    cells.forEachIndexed { index, (colIndex, day, course) ->
        CourseCell(
            course = course,
            sections = ui.sections,
            colIndex = colIndex,
            dayWidth = dayWidth,
            layout = layout,
            metrics = metrics,
            isTodayColumn = ui.dateOf(week, day) == todayDate,
            selected = course.id == selectedCourseId,
            clock = clock,
            entranceDelayMs = index * step,
            entranceEpoch = entranceEpoch,
            onClick = { onCourseClick(course) },
        )
    }
}

/**
 * 单个课块：绝对坐标 + 内容。
 *
 * 单独拆成一个 composable 是为了**订阅边界**。块底的「剩 X 分」每分钟要变，但只有
 * 「今天那一列」需要它；如果这段逻辑留在 [CourseOverlay] 的 forEach 里，读一次时钟
 * 就会让整层（含另外四列）跟着重组。拆出来之后，今天这一列的几个块各自订阅分钟，
 * 其余列根本不读那个 State，一分钟一次的重组就只落在会变的那几个字上。
 */
@Composable
private fun CourseCell(
    course: CourseEntity,
    sections: List<SectionTimeEntity>,
    colIndex: Int,
    dayWidth: Dp,
    layout: GridLayout,
    metrics: GridMetrics,
    isTodayColumn: Boolean,
    selected: Boolean,
    clock: State<LocalDateTime>,
    /** 这一块在入场序列里的起播延迟（序号 × 节拍，见 entranceStepMs） */
    entranceDelayMs: Long,
    entranceEpoch: Int,
    onClick: () -> Unit,
) {
    val startIndex = course.startSection.coerceIn(1, sections.size)
    val endIndex = course.endSection.coerceIn(startIndex, sections.size)
    val span = endIndex - startIndex + 1
    val preset = LocalCoursePreset.current
    val isDark = LocalIsDark.current

    // 只有今天这一列读时钟：正在上的课，块底显示「剩 X 分」
    val remainingMin =
        if (isTodayColumn) {
            val now = clock.value
            val startTime = TimeUtils.parseTime(sections[startIndex - 1].startTime)
            val endTime = TimeUtils.parseTime(sections[endIndex - 1].endTime)
            if (
                startTime != null &&
                endTime != null &&
                !now.toLocalTime().isBefore(startTime) &&
                now.toLocalTime().isBefore(endTime)
            ) {
                Duration
                    .between(now.toLocalTime(), endTime)
                    .toMinutes()
                    .toInt()
                    .coerceAtLeast(1)
            } else {
                null
            }
        } else {
            null
        }

    Box(
        Modifier
            .offset(
                x = metrics.timeAxisWidth + dayWidth * colIndex + 1.dp,
                y = layout.sectionTop(startIndex - 1) + 1.5.dp,
            ).width(dayWidth - 2.dp)
            .height(metrics.sectionHeight * span - 3.dp),
    ) {
        CourseBlock(
            course = course,
            preset = preset,
            isDark = isDark,
            modifier = Modifier.fillMaxSize(),
            selected = selected,
            // 逐个入场：延迟由序列序号给出（见 CourseOverlay）。
            // 回合由 entranceEpoch 打点而不是靠重新组合：快速来回切 tab 时页面留在组合里，
            // 级联不会自己重播（真机上就是「慢切有、快切没有」）
            entranceDelayMs = entranceDelayMs,
            entranceKey = entranceEpoch,
            remainingMin = remainingMin,
            metrics = metrics,
            onClick = onClick,
        )
    }
}
