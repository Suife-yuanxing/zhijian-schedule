package com.zhijian.schedule.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.metal.LocalMetalClock
import com.zhijian.schedule.core.ui.metal.metalPointer
import com.zhijian.schedule.core.ui.metal.metalSurface
import com.zhijian.schedule.core.ui.metal.rememberMetalTouchState

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
    val metal = LocalCoursePreset.current.metal
    if (metal != null) {
        MetalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            hapticType = hapticType,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier =
            modifier.buttonFeedback(
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
        modifier =
            modifier.buttonFeedback(
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
        modifier =
            modifier.buttonFeedback(
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
    val metal = LocalCoursePreset.current.metal
    val metalClock = LocalMetalClock.current
    if (metal != null && metalClock != null) {
        MetalIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            hapticType = hapticType,
            content = content,
        )
        return
    }
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier =
            modifier.buttonFeedback(
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

/**
 * 金属按钮（水银镜面主题专用）：整块按钮是一枚抛光金属片。
 *
 * 与 TapButton 的关系：**同一套交互语义**（按下回弹、可选触觉、禁用态），只是把 M3 的
 * 填充色换成了实时材质。按钮的「按下」同时驱动两件事——外层缩放与着色器里的凹陷光斑，
 * 两者共用一个 [MetalTouchState]，所以视觉上是一次物理动作，而不是两个动画叠在一起。
 *
 * 非金属主题下自动退回 [TapButton]（零风险：其余 6 套主题的按钮外观一字不变）。
 */
@Composable
fun MetalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    prominent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val metal = LocalCoursePreset.current.metal
    if (metal == null) {
        TapButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            hapticType = hapticType,
            contentPadding = contentPadding,
            content = content,
        )
        return
    }
    val colors = LocalCourseColors.current
    val clock = LocalMetalClock.current
    val touch = rememberMetalTouchState()
    val shape = RoundedCornerShape(percent = 50)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .metalSurface(
                    shape = shape,
                    touch = touch,
                    clock = clock,
                    chromeOverride = if (prominent) 0.98f else 0.72f,
                    faceOverride = if (prominent) colors.accent.copy(alpha = 1f) else null,
                ).metalPointer(touch)
                .buttonFeedback(
                    interactionSource = source,
                    pressedScale = 0.965f,
                    enabled = enabled,
                    hapticType = hapticType,
                ).clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ).padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (prominent) colors.onAccent else colors.text) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 金属图标按钮：一枚圆形水银键帽。
 *
 * 尺寸保持 48dp（M3 IconButton 的触控下限），但视觉上是**独立的一枚金属件**——
 * 圆角取 50%，着色器把它画成球冠，所以图标周围有一圈随倾角滑动的镜面。
 */
@Composable
fun MetalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    content: @Composable () -> Unit,
) {
    val clock = LocalMetalClock.current
    val touch = rememberMetalTouchState()
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier =
            modifier
                .size(48.dp)
                .metalSurface(shape = shape, touch = touch, clock = clock, chromeOverride = 0.90f)
                .metalPointer(touch)
                .buttonFeedback(
                    interactionSource = source,
                    pressedScale = 0.90f,
                    enabled = enabled,
                    hapticType = hapticType,
                ).clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
