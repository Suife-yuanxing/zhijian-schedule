package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.selectedPop

/** 偏好选项胶囊：同一套外观被主题 / 深色模式 / 网格风格 / 提醒分钟数四处复用 */
@Composable
internal fun ProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    FilterChip(
        selected = selected,
        onClick = {
            onClick()
        },
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        interactionSource = source,
        colors = profileChipColors(),
        border = profileChipBorder(selected),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .pressScale(source, pressedScale = 0.94f)
            .selectedPop(selected),
    )
}

/** 偏好选项：选中=强调色描边 + 浅色底 + 加粗；只保留按压回弹，选中不额外震动 */
@Composable
private fun profileChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = LocalCourseColors.current.textMuted,
    selectedContainerColor = LocalCourseColors.current.accent.copy(alpha = ACCENT_SELECTED_ALPHA),
    selectedLabelColor = LocalCourseColors.current.text,
)

@Composable
private fun profileChipBorder(selected: Boolean): BorderStroke {
    val colors = LocalCourseColors.current
    return BorderStroke(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) colors.accent else colors.border,
    )
}
