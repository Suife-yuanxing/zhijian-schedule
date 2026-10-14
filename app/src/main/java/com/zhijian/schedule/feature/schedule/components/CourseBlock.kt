package com.zhijian.schedule.feature.schedule.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.ThemePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.util.TimeUtils

/**
 * 统一课块渲染：透明填充 + 左色条 + 主题文字色（方案 5.2 ④）。
 * 交互（扁平无阴影）：按压 0.94 回弹；详情打开期间「弹起 + 实色描边 + 填充加深」，
 * 关闭动作一开始即快速回落（见 [Motion.SelectionExit]），与面板滑出并行完成。
 */
@Composable
fun CourseBlock(
    course: CourseEntity,
    preset: ThemePreset,
    colors: CourseColors,
    isDark: Boolean,
    startTime: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val color = Color(course.color)
    val fillAlpha = if (isDark) preset.blockStyle.fillAlphaDark else preset.blockStyle.fillAlphaLight
    val shape = RoundedCornerShape(preset.radius.blockDp.dp)
    val compact = (course.endSection - course.startSection) < 1
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()

    // 同一目标值双向换规格：进入弹跳、退出干脆（animateFloatAsState 每次开跑都取最新 spec）
    val lift by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) Motion.SelectionEnter else Motion.SelectionExit,
        label = "blockLift",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Motion.PressSpring,
        label = "blockPress",
    )
    val ring = lift.coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .zIndex(if (selected) 1f else 0f)
            .graphicsLayer {
                val scale = press * (1f + 0.045f * lift)
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
            }
            .background(color.copy(alpha = fillAlpha + 0.10f * ring))
            .border(2.dp, color.copy(alpha = ring), shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .width(preset.blockStyle.barWidthDp.dp)
                .fillMaxHeight()
                .background(color)
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = 3.dp)
        ) {
            Text(
                text = course.name,
                color = colors.text,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 2 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                if (startTime.isNotBlank()) {
                    Text(
                        text = startTime,
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                    )
                }
                if (course.room.isNotBlank()) {
                    Text(
                        text = course.room,
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
