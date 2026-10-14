package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 面板「目标可见性」变化：点遮罩 / 返回 / 下滑越阈值让面板开始滑出的瞬间即为 false。
     * onDismissRequest 要等滑出动画播完才回调，不能用于提前恢复选中高亮，改盯 targetValue
     * （与 M3 淡出遮罩同一时机）。
     */
    onTargetVisibilityChange: (Boolean) -> Unit = {},
) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    val editSource = remember { MutableInteractionSource() }
    val deleteSource = remember { MutableInteractionSource() }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden }
            .drop(1) // 忽略订阅瞬间的初始值（此时内部 show() 还没执行）
            .distinctUntilChanged()
            .collect(onTargetVisibilityChange)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            // 色块与标题先弹入，信息行、按钮随后逐级上浮（面板滑入时完成编排）
            val chipScale = popIn()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        }
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
            StaggerIn(index = 1) {
                DetailRow("时间", "${TimeUtils.dayName(course.dayOfWeek)} · 第 ${course.startSection}-${course.endSection} 节")
            }
            StaggerIn(index = 2) {
                DetailRow("周次", WeekMask.toExpression(course.weekMask))
            }
            if (course.room.isNotBlank()) {
                StaggerIn(index = 3) { DetailRow("教室", course.room) }
            }
            if (course.teacher.isNotBlank()) {
                StaggerIn(index = 4) { DetailRow("教师", course.teacher) }
            }
            if (course.note.isNotBlank()) {
                StaggerIn(index = 5) { DetailRow("备注", course.note) }
            }
            Spacer(Modifier.height(18.dp))
            StaggerIn(index = 6) {
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
}

/** 弹入落点：先小后大带一点过冲，与课块的按压反馈同源 */
@Composable
private fun popIn(delayMs: Long = 60): Float {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        arrived = true
    }
    val scale by animateFloatAsState(
        targetValue = if (arrived) 1f else 0.3f,
        animationSpec = Motion.PopSpring,
        label = "chipPopIn",
    )
    return scale
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
