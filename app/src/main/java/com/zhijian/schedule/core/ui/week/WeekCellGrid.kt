package com.zhijian.schedule.core.ui.week

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 周格阵：5 列全览的周次选择面（方案 A，2026-11-07 拍板，见 docs/specs/2026-11-07）。
 *
 * ## 为什么格阵能回来
 * 旧 4×5 格阵被移除的原因是「页面滚不动」：整块容器挂 detectDragGestures 且不判轴向，
 * 位移一过 touchSlop 就 consume()，纵向滑动再也交不到父级 verticalScroll 手里。
 * 根因是「无条件 consume」，不是「格阵」。这里沿用周条验证过的轴向判定：
 * 位移先比横纵分量，横向占优才认成拖选并 consume；纵向占优（含平局）一格都不 consume。
 *
 * ## 全览的价值
 * 全部周次同屏：占用点、本周下划线、已选形态一眼扫完，不需要滚动锚点，
 * 也不需要 LazyRow——格阵比周条还简单一层：命中测试用的 bounds 不会随滚动失效。
 *
 * @param currentWeek 本周（画下划线，之前的周淡化）；null 则全部按普通周画
 * @param occupied 已有其他课程的周次，格右下角点一个圆点提示
 */
@Composable
fun WeekCellGrid(
    totalWeeks: Int,
    weeks: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    currentWeek: Int? = null,
    occupied: Set<Int> = emptySet(),
) {
    val haptics = LocalHapticFeedback.current
    val bounds = remember(totalWeeks) { mutableStateMapOf<Int, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentWeeks by rememberUpdatedState(weeks)
    val currentToggle by rememberUpdatedState(onToggle)

    /** 屏幕坐标（窗口系）命中哪一格；拖选起点与途中每一帧都走这里 */
    fun hitTest(local: Offset): Int? = bounds.entries.firstOrNull { it.value.contains(local + gridOrigin) }?.key

    /** 拖选：同一手势内方向一致（连加路上不删、连删路上不加），来回划不抖动 */
    fun applyAt(
        local: Offset,
        add: Boolean,
    ) {
        val hit = hitTest(local) ?: return
        if (currentWeeks.contains(hit) != add) currentToggle(hit)
    }

    Column(
        modifier =
            modifier
                .onGloballyPositioned { gridOrigin = it.positionInWindow() }
                .pointerInput(totalWeeks) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var horizontal: Boolean? = null // null=未决 true=横向拖选 false=交还父级
                        var add = true
                        // 事件流只在一处取，也只在一处退：手指抬起或指针消失即返回。
                        while (true) {
                            val change =
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) return@awaitEachGesture
                            val delta = change.position - start
                            val decision =
                                resolveDragAxis(
                                    dx = delta.x,
                                    dy = delta.y,
                                    touchSlop = viewConfiguration.touchSlop,
                                )
                            if (horizontal == null && decision != DragAxis.NONE) {
                                horizontal = decision == DragAxis.HORIZONTAL
                                if (horizontal) {
                                    val hit = hitTest(start)
                                    if (hit != null) {
                                        add = !currentWeeks.contains(hit)
                                        currentToggle(hit)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            }
                            if (horizontal == true) {
                                change.consume()
                                applyAt(change.position, add)
                            }
                        }
                    }
                },
        verticalArrangement = Arrangement.spacedBy(WEEK_GRID_GAP),
    ) {
        val rows = (1..totalWeeks).chunked(WEEK_GRID_COLUMNS)
        for (chunk in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(WEEK_GRID_GAP)) {
                chunk.forEach { week ->
                    WeekCell(
                        title = "$week",
                        subtitle = null,
                        visual =
                            resolveWeekCellVisual(
                                selected = weeks.contains(week),
                                current = currentWeek == week,
                                past = currentWeek != null && week < currentWeek,
                            ),
                        occupied = occupied.contains(week),
                        isCurrent = currentWeek == week,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = WEEK_GRID_CELL_MIN)
                                .onGloballyPositioned { coords ->
                                    bounds[week] = Rect(coords.positionInWindow(), coords.size.toSize())
                                },
                        onClick = { currentToggle(week) },
                    )
                }
                // 末行不满 5 格补透明占位：周格保持与上行等宽，不会被 weight 拉成宽条
                repeat(WEEK_GRID_COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 每行格数：360dp 屏 5 列每格约 60dp，数字与占用点都放得下 */
internal const val WEEK_GRID_COLUMNS = 5

/** 单格最小高度：与周条一致的可点击尺寸 */
internal val WEEK_GRID_CELL_MIN = 44.dp

/** 格间距：实心选中块之间留 8dp 呼吸，发丝描边互不贴靠 */
internal val WEEK_GRID_GAP = 8.dp

/** 拖动轴向的判定结果 */
internal enum class DragAxis {
    /** 位移还没过 touchSlop：继续观察，谁都不 consume */
    NONE,

    /** 横向占优：认成拖选 */
    HORIZONTAL,

    /** 纵向占优或平局：交还父级滚动 */
    VERTICAL,
}

/**
 * 起手位移到轴向的判定。平局判纵向：拖选丢一帧无感，页面滚不动是实打实的故障。
 * 纯函数：手势与 JVM 单测共用同一份真值。
 */
internal fun resolveDragAxis(
    dx: Float,
    dy: Float,
    touchSlop: Float,
): DragAxis =
    when {
        hypot(dx, dy) < touchSlop -> DragAxis.NONE
        abs(dx) > abs(dy) -> DragAxis.HORIZONTAL
        else -> DragAxis.VERTICAL
    }
