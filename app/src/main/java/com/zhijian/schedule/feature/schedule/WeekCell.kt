package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale
import java.time.LocalDate

/** 周格：选中实心弹跳、本周主色底+描边、过去淡化（与日详情速选 pill 同一语言） */
@Composable
internal fun WeekCell(
    week: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    selected: Boolean,
    isCurrentWeek: Boolean,
    past: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val popScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = Motion.PopSpring,
        label = "weekCellPop",
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.accent
            isCurrentWeek -> colors.accent.copy(alpha = 0.12f)
            else -> colors.surfaceVariant
        },
        animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        label = "weekCellBg",
    )
    // 未选中的方块保留一丝细描边，块面更挺括；本周用主色描边提亮
    val ring by animateColorAsState(
        targetValue = when {
            selected -> Color.Transparent
            isCurrentWeek -> colors.accent
            else -> colors.border.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = Motion.DurFast),
        label = "weekCellRing",
    )
    // 文字/日期与背景同一 tween 同步过渡：避免「文字瞬间换色、背景还在渐变」造成的
    // 半途配色（如深字压在实心主色上）短暂错渲染
    val titleColor by animateColorAsState(
        targetValue = when {
            selected -> colors.onAccent
            isCurrentWeek -> colors.accent
            past -> colors.textMuted
            else -> colors.text
        },
        animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        label = "weekCellTitle",
    )
    val dateColor by animateColorAsState(
        targetValue = when {
            selected -> colors.onAccent.copy(alpha = 0.92f)
            isCurrentWeek -> colors.accent.copy(alpha = 0.8f)
            past -> colors.textMuted.copy(alpha = 0.7f)
            else -> colors.textMuted
        },
        animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        label = "weekCellDate",
    )
    val shape = RoundedCornerShape(13.dp)

    Column(
        modifier
            .pressScale(pressSource, pressedScale = 0.94f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }
            .clip(shape)
            .background(background)
            .border(1.5.dp, ring, shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第 $week 周",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = weekRangeText(startDate, endDate),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            color = dateColor,
        )
    }
}

/** 「10.12-18」；跨月时补月份「10.30-11.5」 */
private fun weekRangeText(start: LocalDate, end: LocalDate): String =
    if (start.monthValue == end.monthValue) {
        "${start.monthValue}.${start.dayOfMonth}-${end.dayOfMonth}"
    } else {
        "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
    }
