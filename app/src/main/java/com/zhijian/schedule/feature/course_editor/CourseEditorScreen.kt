package com.zhijian.schedule.feature.course_editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.selectedPop
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.util.TimeUtils

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
                val saveColor by animateColorAsState(
                    targetValue = if (ui.canSave) colors.accent else colors.textMuted,
                    animationSpec = tween(durationMillis = Motion.DurFast),
                    label = "saveColor",
                )
                TextButton(onClick = { viewModel.save() }, enabled = ui.canSave) {
                    Text(
                        text = "保存",
                        color = saveColor,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                value = ui.name,
                onValueChange = { viewModel.setField(name = it) },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedTextField(
                    value = ui.room,
                    onValueChange = { viewModel.setField(room = it) },
                    label = { Text("上课教室") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
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
                    FilterChip(
                        selected = ui.dayOfWeek == day,
                        onClick = { viewModel.setField(dayOfWeek = day) },
                        label = { Text(TimeUtils.dayName(day).removePrefix("周")) },
                        modifier = Modifier.selectedPop(ui.dayOfWeek == day),
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
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.setAllWeeks() }) { Text("全选") }
                TextButton(onClick = { viewModel.setParity(odd = true) }) { Text("单周") }
                TextButton(onClick = { viewModel.setParity(odd = false) }) { Text("双周") }
                TextButton(onClick = { viewModel.clearWeeks() }) { Text("清空") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..ui.totalWeeks).forEach { w ->
                    val selected = ui.weeks.contains(w)
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.toggleWeek(w) },
                        label = { Text("$w", fontSize = 12.sp) },
                        modifier = Modifier.selectedPop(selected, popScale = 1.1f),
                    )
                }
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
                        animationSpec = tween(durationMillis = Motion.DurFast),
                        label = "swatchRing",
                    )
                    Box(
                        Modifier
                            .size(30.dp)
                            .pressScale(pressSource, pressedScale = 0.88f)
                            .graphicsLayer {
                                scaleX = popScale
                                scaleY = popScale
                            }
                            .clip(CircleShape)
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
                value = ui.note,
                onValueChange = { viewModel.setField(note = it) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
            if (ui.isEdit) {
                val deleteSource = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.delete()
                    },
                    interactionSource = deleteSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(deleteSource, pressedScale = 0.97f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5484D),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("删除课程")
                }
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
private fun SectionDropdown(
    label: String,
    selected: Int,
    range: IntRange,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label, color = colors.text)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.textMuted)
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
    (android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    ))
