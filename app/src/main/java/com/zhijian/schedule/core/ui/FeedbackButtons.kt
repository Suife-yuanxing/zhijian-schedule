package com.zhijian.schedule.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * 按钮交互的统一入口：按压回弹；触觉默认关闭。
 * 只有完成态 / 破坏性操作 / 长按才显式传 hapticType，普通按钮不要再震。
 */
@Composable
fun TapButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.buttonFeedback(
            interactionSource = source,
            pressedScale = 0.97f,
            enabled = enabled,
            hapticType = hapticType,
        ),
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun TapTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.buttonFeedback(
            interactionSource = source,
            pressedScale = 0.93f,
            enabled = enabled,
            hapticType = hapticType,
        ),
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun TapOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.buttonFeedback(
            interactionSource = source,
            pressedScale = 0.97f,
            enabled = enabled,
            hapticType = hapticType,
        ),
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun TapIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.buttonFeedback(
            interactionSource = source,
            pressedScale = 0.88f,
            enabled = enabled,
            hapticType = hapticType,
        ),
        enabled = enabled,
        colors = colors,
        interactionSource = source,
        content = content,
    )
}
