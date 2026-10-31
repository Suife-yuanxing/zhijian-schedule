package com.zhijian.schedule.feature.courseeditor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.metal.MetalFields
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.selectedPop
import com.zhijian.schedule.core.ui.week.WeekCell
import com.zhijian.schedule.core.ui.week.resolveWeekCellVisual
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditorScreen(
    onDone: () -> Unit,
    viewModel: CourseEditorViewModel = hiltViewModel(),
) {
    val ui = viewModel.uiState
    val colors = LocalCourseColors.current
    val preset = LocalCoursePreset.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(ui.saved) {
        if (ui.saved) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(
                title = if (ui.isEdit) "编辑课程" else "添加课程",
                onBack = onDone,
            ) {
                if (ui.isEdit) {
                    val deleteSource = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = { viewModel.delete() },
                        interactionSource = deleteSource,
                        modifier =
                            Modifier.buttonFeedback(
                                deleteSource,
                                pressedScale = 0.92f,
                                hapticType = HapticFeedbackType.LongPress,
                            ),
                    ) {
                        Text(
                            text = "删除",
                            color = Color(0xFFE5484D),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        containerColor = colors.bg,
    ) { padding ->
        if (ui.loading) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                value = ui.name,
                onValueChange = { viewModel.setField(name = it) },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = ui.room,
                    onValueChange = { viewModel.setField(room = it) },
                    label = { Text("上课教室") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = ui.teacher,
                    onValueChange = { viewModel.setField(teacher = it) },
                    label = { Text("授课教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("星期")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                (1..7).forEach { day ->
                    val selected = ui.dayOfWeek == day
                    val source = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.setField(dayOfWeek = day)
                        },
                        label = {
                            Text(
                                TimeUtils.dayName(day).removePrefix("周"),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        interactionSource = source,
                        colors = editorChipColors(),
                        border = editorChipBorder(selected),
                        modifier =
                            Modifier
                                .pressScale(source, pressedScale = 0.92f)
                                .selectedPop(selected),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("节次")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionDropdown(
                    label = "第 ${ui.startSection} 节",
                    selected = ui.startSection,
                    range = 1..12,
                    onSelect = { s ->
                        viewModel.setField(startSection = s, endSection = maxOf(s, ui.endSection))
                    },
                )
                Text(" 至 ", color = colors.textMuted)
                SectionDropdown(
                    label = "第 ${ui.endSection} 节",
                    selected = ui.endSection,
                    range = 1..12,
                    onSelect = { e ->
                        viewModel.setField(endSection = e, startSection = minOf(e, ui.startSection))
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "大节 ${(ui.startSection + 1) / 2}-${(ui.endSection + 1) / 2}",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("周次")
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${ui.weeks.size}/${ui.totalWeeks} 周",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全选", "单周", "双周", "清空").forEach { label ->
                    PresetSegment(
                        text = label,
                        modifier = Modifier.weight(1f),
                    ) {
                        when (label) {
                            "全选" -> viewModel.setAllWeeks()
                            "单周" -> viewModel.setParity(odd = true)
                            "双周" -> viewModel.setParity(odd = false)
                            "清空" -> viewModel.clearWeeks()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            WeekMaskGrid(
                totalWeeks = ui.totalWeeks,
                weeks = ui.weeks,
                onToggle = viewModel::toggleWeek,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结果", color = colors.textMuted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = WeekMask.describe(WeekMask.fromWeeks(ui.totalWeeks, ui.weeks)),
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("颜色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                preset.coursePalette.forEach { paletteColor ->
                    val selected = ui.color == paletteColor.toArgbInt()
                    val pressSource = remember { MutableInteractionSource() }
                    val popScale by animateFloatAsState(
                        targetValue = if (selected) 1.16f else 1f,
                        animationSpec = Motion.PopSpring,
                        label = "swatchPop",
                    )
                    val ring by animateColorAsState(
                        targetValue = if (selected) colors.text else Color.Transparent,
                        animationSpec = tween(durationMillis = Motion.DUR_FAST),
                        label = "swatchRing",
                    )
                    Box(
                        Modifier
                            .size(30.dp)
                            .pressScale(pressSource, pressedScale = 0.88f)
                            .graphicsLayer {
                                scaleX = popScale
                                scaleY = popScale
                            }.clip(CircleShape)
                            .background(paletteColor)
                            .border(2.dp, ring, CircleShape)
                            .clickable(
                                interactionSource = pressSource,
                                indication = LocalIndication.current,
                            ) {
                                viewModel.setField(color = paletteColor.toArgbInt())
                            },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                value = ui.note,
                onValueChange = { viewModel.setField(note = it) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
            val saveContainer by animateColorAsState(
                targetValue = if (ui.canSave) colors.accent else colors.textMuted.copy(alpha = 0.14f),
                animationSpec = tween(durationMillis = Motion.DUR_FAST),
                label = "saveContainer",
            )
            val saveContent by animateColorAsState(
                targetValue = if (ui.canSave) colors.onAccent else colors.textMuted,
                animationSpec = tween(durationMillis = Motion.DUR_FAST),
                label = "saveContent",
            )
            val saveSource = remember { MutableInteractionSource() }
            Button(
                onClick = { viewModel.save() },
                enabled = ui.canSave,
                interactionSource = saveSource,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .buttonFeedback(
                            saveSource,
                            pressedScale = 0.97f,
                            enabled = ui.canSave,
                        ),
                colors =
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = saveContainer,
                        contentColor = saveContent,
                        disabledContainerColor = saveContainer,
                        disabledContentColor = saveContent,
                    ),
            ) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val colors = LocalCourseColors.current
    Text(
        text = text,
        color = colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun editorChipColors() =
    FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = LocalCourseColors.current.textMuted,
        selectedContainerColor = LocalCourseColors.current.accent.copy(alpha = 0.20f),
        selectedLabelColor = LocalCourseColors.current.text,
    )

@Composable
private fun editorChipBorder(selected: Boolean): BorderStroke {
    val colors = LocalCourseColors.current
    return BorderStroke(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) colors.accent else colors.border,
    )
}

/** 快捷档分段（动作语义：点一下执行一次，无选中态） */
@Composable
private fun PresetSegment(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(source, pressedScale = 0.93f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, color = colors.text, fontWeight = FontWeight.Medium)
    }
}

/**
 * 周次多选周格：与选周面板同一套 WeekCell 视觉（紧凑态）。
 * 拖动连选是加速器：按在空格上=连加，按在选中格上=连删；单击逐格始终可用。
 */
@Composable
private fun WeekMaskGrid(
    totalWeeks: Int,
    weeks: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val rows = remember(totalWeeks) { (1..totalWeeks).chunked(4) }
    // 拖动命中用窗口坐标：格子存 positionInWindow 的 Rect，拖拽的本地坐标加上网格原点换算
    val bounds = remember(totalWeeks) { mutableStateMapOf<Int, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var dragAdd by remember { mutableStateOf(true) }
    val currentWeeks by rememberUpdatedState(weeks)
    val currentToggle by rememberUpdatedState(onToggle)

    fun applyAt(pos: Offset) {
        val window = pos + gridOrigin
        val hit = bounds.entries.firstOrNull { it.value.contains(window) }?.key ?: return
        val contains = currentWeeks.contains(hit)
        // 同一手势内方向一致：连加路上不删、连删路上不加，来回划不抖动
        if (dragAdd != contains) currentToggle(hit)
    }

    Box(
        Modifier
            .onGloballyPositioned { gridOrigin = it.positionInWindow() }
            .pointerInput(totalWeeks) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val window = pos + gridOrigin
                        val hit = bounds.entries.firstOrNull { it.value.contains(window) }?.key
                        if (hit != null) {
                            dragging = true
                            dragAdd = !currentWeeks.contains(hit)
                            currentToggle(hit)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragging) {
                            change.consume()
                            applyAt(change.position)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            rows.forEach { rowWeeks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowWeeks.forEach { week ->
                        val selected = currentWeeks.contains(week)
                        WeekCell(
                            title = "$week",
                            subtitle = null,
                            visual = resolveWeekCellVisual(selected, current = false, past = false),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        bounds[week] =
                                            Rect(coords.positionInWindow(), coords.size.toSize())
                                    },
                            onClick = { currentToggle(week) },
                        )
                    }
                    repeat(4 - rowWeeks.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionDropdown(
    label: String,
    selected: Int,
    range: IntRange,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.PopSpring,
        label = "sectionArrow",
    )
    Box {
        Row(
            Modifier
                .pressScale(source, pressedScale = 0.93f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                ) {
                    expanded = true
                }.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier =
                    Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = arrow },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            range.forEach { n ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "第 $n 节（大节 ${(n + 1) / 2}）",
                            color = if (n == selected) colors.accent else colors.text,
                        )
                    },
                    trailingIcon =
                        if (n == selected) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        onSelect(n)
                    },
                )
            }
        }
    }
}

/** Compose Color → ARGB Int */
private fun Color.toArgbInt(): Int =
    (
        android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt(),
        )
    )
