package com.zhijian.schedule.feature.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.model.ImportChange
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback

@Composable
internal fun PreviewContent(
    changes: List<ImportChange>,
    summary: String,
    hasConflict: Boolean,
    applyConflicts: Boolean,
    onToggleConflicts: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    sectionHint: String? = null,
) {
    val colors = LocalCourseColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TapTextButton(onClick = onCancel) { Text("重新选择") }
        }
        if (sectionHint != null) {
            Text(
                text = sectionHint,
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider(color = colors.border)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(changes) { idx, change ->
                StaggerIn(index = idx) {
                    ChangeRow(change, overwriteConflicts = applyConflicts)
                }
            }
        }
        if (hasConflict) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("覆盖手动编辑的课程", color = colors.text, fontSize = 14.sp)
                    Text(
                        "关闭后跳过你手动改过的课程（冲突项）",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                    )
                }
                JellySwitch(
                    checked = applyConflicts,
                    onCheckedChange = {
                        onToggleConflicts(it)
                    },
                )
            }
        }
        val confirmSource = remember { MutableInteractionSource() }
        Button(
            onClick = onConfirm,
            interactionSource = confirmSource,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // 确认按钮浮在手势条正上方
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .buttonFeedback(confirmSource, pressedScale = 0.97f),
        ) { Text("确认导入") }
    }
}

@Composable
internal fun ChangeRow(
    change: ImportChange,
    overwriteConflicts: Boolean = false,
) {
    val colors = LocalCourseColors.current
    val (label, badgeColor) =
        when (change.kind) {
            ImportChangeKind.ADDED -> "新增" to Color(0xFF2BB673)
            ImportChangeKind.CHANGED -> "变更" to Color(0xFFF59E0B)
            ImportChangeKind.REMOVED -> "取消" to Color(0xFFEF4444)
            ImportChangeKind.UNCHANGED -> "无变化" to colors.textMuted
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(label, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = change.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            if (change.detail.isNotBlank()) {
                Text(change.detail, fontSize = 12.sp, color = colors.textMuted)
            }
            if (change.conflict) {
                Text(
                    if (overwriteConflicts) {
                        "你手动编辑过，确认后将覆盖为新数据"
                    } else {
                        "你手动编辑过，将保留本地版本"
                    },
                    fontSize = 11.sp,
                    color = Color(0xFFEF4444),
                )
            }
        }
    }
}
