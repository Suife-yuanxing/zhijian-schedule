package com.zhijian.schedule.feature.schedule
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.week.WeekCell
import com.zhijian.schedule.core.ui.week.resolveWeekCellVisual
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 周选择面板：4 列周格（「第 N 周」+ 日期范围），点选即收起并切换周页。
 *
 * 视觉沿用全局选中语言（与日详情速选 pill 一致）：
 * 选中 = 实心主色 + 弹跳；本周 = 主色描边 + 浅底；过去 = 淡化。
 * 格子按行 StaggerIn 逐级登场，随面板滑入完成编排。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekPickerSheet(
    ui: ScheduleUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onTargetVisibilityChange: (Boolean) -> Unit = {},
) {
    val colors = LocalCourseColors.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden }
            .drop(1) // 忽略订阅瞬间的初始值（此时内部 show() 还没执行）
            .distinctUntilChanged()
            .collect(onTargetVisibilityChange)
    }

    // 点选后周页仍在滚动，selectedWeek 会短暂经过中间周；把高亮锁定在已选项，
    // 避免面板滑出期间出现「高亮追逐滚动页」的扫动与半途配色
    var pinnedWeek by remember { mutableStateOf<Int?>(null) }

    // 点周即选：页面切换与面板滑出并行，不互相等
    val pick: (Int) -> Unit = { week ->
        pinnedWeek = week
        onSelect(week)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 水平内边距不放在这里，而是随行入场层（StaggerIn contentPadding）下发：
        // 选中格弹跳会越出行 bounds，alpha 离屏层以自身 bounds 裁剪，垫在图层内才不被切；
        // 行距/间距同步收窄补偿，各元素绝对位置与原来逐像素一致
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 27.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StaggerIn(index = 0) {
                    Text(
                        text = "选择周次",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                ui.currentWeek?.takeIf { it != ui.selectedWeek }?.let { cw ->
                    StaggerIn(index = 1) {
                        TapTextButton(onClick = { pick(cw) }) {
                            Text("回到本周", color = colors.accent)
                        }
                    }
                }
            }
            // 3dp + 行图层内 3dp 上垫 = 原来的 6dp
            Spacer(Modifier.height(3.dp))

            (1..ui.totalWeeks).toList().chunked(4).forEachIndexed { rowIdx, rowWeeks ->
                StaggerIn(
                    index = rowIdx + 1,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 3.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        rowWeeks.forEach { week ->
                            WeekCell(
                                title = "第 $week 周",
                                subtitle = weekRangeText(ui.dateOf(week, 1), ui.dateOf(week, 7)),
                                visual =
                                    resolveWeekCellVisual(
                                        selected = week == (pinnedWeek ?: ui.selectedWeek),
                                        current = week == ui.currentWeek,
                                        past = ui.currentWeek != null && week < ui.currentWeek,
                                    ),
                                modifier = Modifier.weight(1f),
                                onClick = { pick(week) },
                            )
                        }
                        repeat(4 - rowWeeks.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                // 行间距必须放在 StaggerIn 外层（其内部是 Box，写在里面会叠放失效）；
                // 7dp + 上下各 3dp 垫 = 原来的 13dp，方块间隙不变
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

/** 「10.12-18」；跨月时补月份「10.30-11.5」 */
private fun weekRangeText(
    start: LocalDate,
    end: LocalDate,
): String =
    if (start.monthValue == end.monthValue) {
        "${start.monthValue}.${start.dayOfMonth}-${end.dayOfMonth}"
    } else {
        "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
    }
