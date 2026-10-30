package com.zhijian.schedule.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.metal.MetalFields

/** 设置页的两个编辑弹窗：课表信息、单个小节的上课时间 */

@Composable
internal fun TimetableEditDialog(
    initialName: String,
    initialSemester: String,
    initialStartDate: String,
    initialWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var semester by remember { mutableStateOf(initialSemester) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var weeks by remember { mutableStateOf(initialWeeks) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表信息") },
        text = {
            Column {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课表名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = semester,
                    onValueChange = { semester = it },
                    label = { Text("学期（如 2026 秋季）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("第 1 周周一（yyyy-MM-dd）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text("总周数", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 18, 20, 22).forEach { w ->
                        FilterChip(
                            selected = weeks == w,
                            onClick = {
                                weeks = w
                            },
                            label = { Text("$w", fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TapTextButton(
                hapticType = HapticFeedbackType.Confirm,
                onClick = { onSave(name, semester, startDate, weeks) },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SectionEditDialog(
    section: SectionTimeEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var start by remember { mutableStateOf(section.startTime) }
    var end by remember { mutableStateOf(section.endTime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小节 ${section.sectionNo} 时间") },
        text = {
            Column {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始（HH:mm）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("结束（HH:mm）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TapTextButton(
                hapticType = HapticFeedbackType.Confirm,
                onClick = { onSave(start, end) },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
