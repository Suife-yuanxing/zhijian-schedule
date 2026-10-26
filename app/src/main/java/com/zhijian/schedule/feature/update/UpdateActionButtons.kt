package com.zhijian.schedule.feature.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.buttonFeedback

/**
 * 更新窗口的两种操作按钮：统一的按压缩放反馈。
 * 主操作（[FilledAction]）铺强调色，次操作（[OutlinedAction]）走描边；
 * 窗口主按钮区与「清理下载缓存」确认框共用，所以放在独立文件里。
 */

@Composable
internal fun FilledAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .buttonFeedback(source, pressedScale = 0.97f, enabled = enabled, hapticType = HapticFeedbackType.Confirm),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.35f),
            disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
        ),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun OutlinedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .buttonFeedback(source, pressedScale = 0.97f),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMuted),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
