package com.zhijian.schedule.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard

/*
 * 「我的」页的入口卡控件：大卡（双列主入口）、分组列表卡与行、行间分隔线。
 *
 * 只做外观与点击反馈，不持有任何数据；入口/跳转由调用方注入。
 */

/** 常用入口大卡：主色浅底 + 44dp 图标底座，双列并排作为整页主入口 */
@Composable
internal fun HeroEntryCard(
    modifier: Modifier = Modifier,
    painter: Painter,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    // 图标底座的按压响应：按下缩一点，松手带过冲弹回——卡片整体缩之外，
    // 图标自己「沉一下再浮起来」，页面还没打开就有被按住的手感
    val tileScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = Motion.PressSpring,
        label = "heroTilePress",
    )
    Column(
        modifier
            .buttonFeedback(pressSource, pressedScale = 0.97f)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.accent.copy(alpha = ACCENT_SURFACE_ALPHA))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(13.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .graphicsLayer {
                    scaleX = tileScale
                    scaleY = tileScale
                }.clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = ACCENT_TILE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.textMuted,
        )
    }
}

/** 分组列表卡：多行入口共用一张卡，行间用与文字左对齐的 hairline 分隔 */
@Composable
internal fun GroupedEntryCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCourseColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            // 金属主题：分组列表是一块哑光金属板，行间 hairline 正好读作板上的分割线
            .metalCard(shape = shape, color = colors.surface, tone = CardTone.SURFACE)
            .border(
                1.dp,
                colors.border.copy(
                    alpha =
                        if (LocalCoursePreset.current.metal !=
                            null
                        ) {
                            0.45f
                        } else {
                            1f
                        },
                ),
                shape,
            ),
        content = content,
    )
}

@Composable
internal fun GroupedEntryRow(
    painter: Painter,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // 与入口大卡同款的图标底座按压响应；行本体仍用底色反馈，不缩放，避免分隔线跟着动
    val tileScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.9f else 1f,
        animationSpec = Motion.PressSpring,
        label = "rowTilePress",
    )
    // 尾部箭头按住时往前探一点：方向暗示「要去哪」，松手弹回
    val arrowNudge by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 1f else 0f,
        animationSpec = Motion.PressSpring,
        label = "rowArrowNudge",
    )
    Row(
        Modifier
            .fillMaxWidth()
            // 整行按下用底色反馈，不缩放，避免分隔线跟着动
            .background(
                if (pressed && onClick != null) {
                    colors.surfaceVariant.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                },
            ).then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = tileScale
                    scaleY = tileScale
                }.clip(RoundedCornerShape(11.dp))
                .metalCard(RoundedCornerShape(11.dp), colors.surfaceVariant, CardTone.INSET),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text,
            )
            Text(text = subtitle, fontSize = 11.sp, color = colors.textMuted, maxLines = 1)
        }
        // 不可点击的行不画箭头，避免给出无效的功能暗示
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.7f + 0.3f * arrowNudge),
                modifier =
                    Modifier
                        .size(20.dp)
                        .graphicsLayer { translationX = 3.dp.toPx() * arrowNudge },
            )
        }
    }
}

@Composable
internal fun GroupDivider() {
    val colors = LocalCourseColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp),
        thickness = 1.dp,
        color = colors.border.copy(alpha = 0.6f),
    )
}
