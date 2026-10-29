package com.zhijian.schedule.feature.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapOutlinedButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.selectedPop
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DraftEditSheet(
    draft: CourseDraft,
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (CourseDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(draft) { mutableStateOf(draft.name) }
    var room by remember(draft) { mutableStateOf(draft.room) }
    var teacher by remember(draft) { mutableStateOf(draft.teacher) }
    var day by remember(draft) { mutableStateOf(draft.dayOfWeek) }
    var startSection by remember(draft) { mutableStateOf(draft.startSection) }
    var endSection by remember(draft) { mutableStateOf(draft.endSection) }
    var weeks by remember(draft) { mutableStateOf(WeekMask.weeks(draft.weekMask).toSet()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "校对课程",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("上课教室") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("授课教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            SheetLabel("星期")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { d ->
                    FilterChip(
                        selected = day == d,
                        onClick = {
                            day = d
                        },
                        label = { Text(TimeUtils.dayName(d).removePrefix("周")) },
                        modifier = Modifier.selectedPop(day == d),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SheetLabel("节次")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionPicker(label = "第 $startSection 节", onSelect = { s ->
                    startSection = s
                    if (endSection < s) endSection = s
                })
                Text(" 至 ", color = colors.textMuted)
                SectionPicker(label = "第 $endSection 节", onSelect = { e ->
                    endSection = e
                    if (startSection > e) startSection = e
                })
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "大节 ${(startSection + 1) / 2}-${(endSection + 1) / 2}",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetLabel("周次")
                Spacer(Modifier.width(8.dp))
                Text("${weeks.size}/$totalWeeks 周", color = colors.textMuted, fontSize = 12.sp)
            }
            Row {
                TapTextButton(onClick = { weeks = (1..totalWeeks).toSet() }) { Text("全选") }
                TapTextButton(onClick = { weeks = (1..totalWeeks).filter { it % 2 == 1 }.toSet() }) { Text("单周") }
                TapTextButton(onClick = { weeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet() }) { Text("双周") }
                TapTextButton(onClick = { weeks = emptySet() }) { Text("清空") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..totalWeeks).forEach { w ->
                    val selected = weeks.contains(w)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            weeks =
                                weeks.toMutableSet().also { set ->
                                    if (w in set) set.remove(w) else set.add(w)
                                }
                        },
                        label = { Text("$w", fontSize = 12.sp) },
                        modifier = Modifier.selectedPop(selected, popScale = 1.1f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TapOutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                ) { Text("删除这条") }
                Spacer(Modifier.weight(1f))
                TapButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSave(
                            draft.copy(
                                name = name.trim(),
                                room = room.trim(),
                                teacher = teacher.trim(),
                                dayOfWeek = day,
                                startSection = startSection,
                                endSection = endSection,
                                weekMask = WeekMask.fromWeeks(totalWeeks, weeks),
                            ),
                        )
                    },
                ) { Text("完成") }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalCourseColors.current.text,
        fontWeight = FontWeight.Medium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(
    label: String,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TapTextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (1..12).forEach { s ->
                DropdownMenuItem(
                    text = { Text("第 $s 节") },
                    onClick = {
                        open = false
                        onSelect(s)
                    },
                )
            }
        }
    }
}
