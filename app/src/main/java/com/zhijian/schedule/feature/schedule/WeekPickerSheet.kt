package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
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
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden }
            .drop(1) // 忽略订阅瞬间的初始值（此时内部 show() 还没执行）
            .distinctUntilChanged()
            .collect(onTargetVisibilityChange)
    }

    // 点周即选：先给触觉反馈，页面切换与面板滑出并行，不互相等
    val pick: (Int) -> Unit = { week ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSelect(week)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        TextButton(onClick = { pick(cw) }) {
                            Text("回到本周", color = colors.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            (1..ui.totalWeeks).toList().chunked(4).forEachIndexed { rowIdx, rowWeeks ->
                StaggerIn(index = rowIdx + 1) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        rowWeeks.forEach { week ->
                            WeekCell(
                                week = week,
                                startDate = ui.dateOf(week, 1),
                                endDate = ui.dateOf(week, 7),
                                selected = week == ui.selectedWeek,
                                isCurrentWeek = week == ui.currentWeek,
                                past = ui.currentWeek != null && week < ui.currentWeek,
                                modifier = Modifier.weight(1f),
                                onClick = { pick(week) },
                            )
                        }
                        repeat(4 - rowWeeks.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                // 行间距必须放在 StaggerIn 外层：其内部是 Box，写在里面会与内容叠放而失效
                Spacer(Modifier.height(13.dp))
            }
        }
    }
}

/** 周格：选中实心弹跳、本周主色底+描边、过去淡化（与日详情速选 pill 同一语言） */
@Composable
private fun WeekCell(
    week: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    selected: Boolean,
    isCurrentWeek: Boolean,
    past: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val popScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = Motion.PopSpring,
        label = "weekCellPop",
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accent
            isCurrentWeek -> colors.accent.copy(alpha = 0.12f)
            else -> colors.surfaceVariant
        },
        animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        label = "weekCellBg",
    )
    // 未选中的方块保留一丝细描边，块面更挺括；本周用主色描边提亮
    val ring by animateColorAsState(
        targetValue = when {
            selected -> Color.Transparent
            isCurrentWeek -> colors.accent
            else -> colors.border.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = Motion.DurFast),
        label = "weekCellRing",
    )
    val shape = RoundedCornerShape(13.dp)

    Column(
        modifier
            .pressScale(pressSource, pressedScale = 0.94f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }
            .clip(shape)
            .background(background)
            .border(1.5.dp, ring, shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第 $week 周",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                selected -> colors.onAccent
                isCurrentWeek -> colors.accent
                past -> colors.textMuted
                else -> colors.text
            },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = weekRangeText(startDate, endDate),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            color = when {
                selected -> colors.onAccent.copy(alpha = 0.92f)
                isCurrentWeek -> colors.accent.copy(alpha = 0.8f)
                past -> colors.textMuted.copy(alpha = 0.7f)
                else -> colors.textMuted
            },
        )
    }
}

/** 「10.12-18」；跨月时补月份「10.30-11.5」 */
private fun weekRangeText(start: LocalDate, end: LocalDate): String =
    if (start.monthValue == end.monthValue) {
        "${start.monthValue}.${start.dayOfMonth}-${end.dayOfMonth}"
    } else {
        "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
    }
