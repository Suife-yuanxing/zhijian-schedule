package com.zhijian.schedule.feature.jw

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.AnimatedGradientCheckMark
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.accentGradient
import com.zhijian.schedule.core.ui.buttonFeedback

/*
 * 导入流程的最后两步：**预览**（变更清单 + 应用/取消）与**结果**（导入完成提示）。
 *
 * 预览表按 [ImportChangeKind] 标出新增 / 变更 / 取消 / 无变化，用户确认后才真正落库，
 * 因此这里只负责把 [ImportPreview] 画出来并回调，不做任何导入判断。
 */

/** 抓取结果的变更预览底部弹层 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JwPreviewSheet(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = "抓取结果 · ${preview.summary()}",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            preview.sectionHint()?.let { hint ->
                Text(text = hint, color = colors.accent, fontSize = 12.sp)
            }
            // 课表信息不会跟着抓取改，但换学校/学期后旧值会留着，提示用户去核对
            preview.infoHint?.let { hint ->
                Text(
                    text = hint,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.height(320.dp)) {
                items(preview.changes) { change ->
                    val label =
                        when (change.kind) {
                            ImportChangeKind.ADDED -> "新增"
                            ImportChangeKind.CHANGED -> "变更"
                            ImportChangeKind.REMOVED -> "取消"
                            ImportChangeKind.UNCHANGED -> "无变化"
                        }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = label,
                            color = colors.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.width(40.dp),
                        )
                        Column {
                            Text(change.name, color = colors.text, fontSize = 14.sp)
                            if (change.detail.isNotBlank()) {
                                Text(change.detail, color = colors.textMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TapTextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                val applySource = remember { MutableInteractionSource() }
                Button(
                    onClick = onApply,
                    interactionSource = applySource,
                    modifier =
                        Modifier
                            .weight(1f)
                            .buttonFeedback(applySource, pressedScale = 0.97f),
                ) {
                    Text("应用变更")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 导入完成提示：确认即返回上一页。
 *
 * 标题左侧那枚勾是**唯一**在弹窗里出现的装饰：抓取课表是整条流程的终点，
 * 用户此刻最想知道的是「成了没有」，一枚落笔的勾比"导入完成"四个字更早被读到。
 * 勾用主题强调色派生（[accentGradient]），因此八套预设下都不会跳出一块外来色。
 */
@Composable
internal fun JwImportDoneDialog(onDismiss: () -> Unit) {
    val colors = LocalCourseColors.current
    // 弹窗出现即视为「已勾上」：这里不需要先 false 再 true，
    // 因为 AlertDialog 每次打开都是新组合，animateFloatAsState 的初值仍取目标值 0f 起点。
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedGradientCheckMark(
                    checked = shown,
                    markSize = 22.dp,
                    gradientColors = remember(colors.accent) { accentGradient(colors.accent) },
                    idleRingColor = colors.accent,
                )
                Spacer(Modifier.width(8.dp))
                Text("导入完成")
            }
        },
        text = { Text("课表已更新，可在周视图查看。") },
        confirmButton = { TapTextButton(onClick = onDismiss) { Text("好的") } },
    )
}
