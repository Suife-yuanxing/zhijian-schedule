package com.zhijian.schedule.feature.schedule
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = LocalIsDark.current
    val tone = remember(course.color, isDark) { courseTone(Color(course.color), isDark) }

    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden }
            .drop(1) // 忽略订阅瞬间的初始值（此时内部 show() 还没执行）
            .distinctUntilChanged()
            .collect(onTargetVisibilityChange)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            val chipScale = popIn()
            val heroShape = RoundedCornerShape(20.dp)
            // 头卡用课程自己的色调铺底，和课表里的色块一一对应
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(heroShape)
                    .background(tone.container)
                    .border(1.dp, tone.line, heroShape)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            }
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(course.color)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = tone.ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeroPill(
                        text = "${TimeUtils.dayName(course.dayOfWeek)} · 第 ${course.startSection}-${course.endSection} 节",
                        tone = tone,
                    )
                    HeroPill(text = WeekMask.toExpression(course.weekMask), tone = tone)
                }
            }

            val hasDetails = course.room.isNotBlank() ||
                course.teacher.isNotBlank() ||
                course.note.isNotBlank()
            if (hasDetails) {
                Spacer(Modifier.height(16.dp))
                StaggerIn(index = 1) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        if (course.room.isNotBlank()) {
                            InfoRow(Icons.Filled.Place, "教室", course.room, tone)
                        }
                        if (course.teacher.isNotBlank()) {
                            InfoRow(Icons.Filled.Person, "教师", course.teacher, tone)
                        }
                        if (course.note.isNotBlank()) {
                            InfoRow(Icons.Filled.Info, "备注", course.note, tone)
                        }
                    }
                }
            }

            Spacer(Modifier.height(if (hasDetails) 20.dp else 16.dp))
            StaggerIn(index = 2) {
                Row {
                    Button(
                        onClick = {
                            onEdit()
                        },
                        interactionSource = editSource,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(editSource, pressedScale = 0.96f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("编辑", fontWeight = FontWeight.SemiBold)
                    }
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
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = Color(0xFFE5484D),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除", color = Color(0xFFE5484D), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

