package com.zhijian.schedule.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    val editSource = remember { MutableInteractionSource() }
    val deleteSource = remember { MutableInteractionSource() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(course.color))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("时间", "${TimeUtils.dayName(course.dayOfWeek)} · 第 ${course.startSection}-${course.endSection} 节")
            DetailRow("周次", WeekMask.toExpression(course.weekMask))
            if (course.room.isNotBlank()) DetailRow("教室", course.room)
            if (course.teacher.isNotBlank()) DetailRow("教师", course.teacher)
            if (course.note.isNotBlank()) DetailRow("备注", course.note)
            Spacer(Modifier.height(18.dp))
            Row {
                OutlinedButton(
                    onClick = onEdit,
                    interactionSource = editSource,
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(editSource, pressedScale = 0.96f),
                ) { Text("编辑") }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    },
                    interactionSource = deleteSource,
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(deleteSource, pressedScale = 0.96f),
                ) {
                    Text("删除", color = Color(0xFFE5484D))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalCourseColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = colors.textMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
