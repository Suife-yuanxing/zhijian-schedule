package com.zhijian.schedule.feature.importer

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask

@Composable
fun OcrReviewContent(
    review: OcrReviewState,
    onUpdate: (Int, CourseDraft) -> Unit,
    onRemove: (Int) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalCourseColors.current
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "识别到 ${review.drafts.size} 门课程",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("点按条目校对，识别结果可能有误", fontSize = 12.sp, color = colors.textMuted)
            }
            TapTextButton(onClick = onBack) { Text("重新选择") }
        }
        if (review.warnings.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF59E0B).copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                review.warnings.take(3).forEach { warning ->
                    Text(warning, fontSize = 12.sp, color = Color(0xFFF59E0B))
                }
            }
        }
        HorizontalDivider(color = colors.border)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(review.drafts) { idx, draft ->
                StaggerIn(index = idx) {
                    DraftRow(draft = draft, onClick = { editingIndex = idx })
                }
            }
        }
        TapButton(
            onClick = onConfirm,
            enabled = review.drafts.isNotEmpty(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
        ) { Text("下一步（${review.drafts.size} 门）") }
    }

    val editIdx = editingIndex
    if (editIdx != null && editIdx in review.drafts.indices) {
        DraftEditSheet(
            draft = review.drafts[editIdx],
            totalWeeks = review.totalWeeks,
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                onUpdate(editIdx, updated)
                editingIndex = null
            },
            onDelete = {
                onRemove(editIdx)
                editingIndex = null
            },
        )
    }
}

@Composable
internal fun DraftRow(
    draft: CourseDraft,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .buttonFeedback(pressSource, pressedScale = 0.98f)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = draft.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text =
                    buildString {
                        append(TimeUtils.dayName(draft.dayOfWeek))
                        append(" · 第${draft.startSection}-${draft.endSection}节")
                        if (draft.room.isNotBlank()) append(" · ${draft.room}")
                        append(" · ${WeekMask.toExpression(draft.weekMask)}")
                    },
                fontSize = 12.sp,
                color = colors.textMuted,
            )
        }
        if (draft.teacher.isNotBlank()) {
            Text(draft.teacher, fontSize = 12.sp, color = colors.textMuted)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Filled.Edit,
            contentDescription = "修改",
            tint = colors.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
