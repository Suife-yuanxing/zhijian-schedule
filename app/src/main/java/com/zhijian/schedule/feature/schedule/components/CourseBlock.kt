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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.ThemePreset
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.cascadeIn
import com.zhijian.schedule.core.util.WeekMask

/**
 * 统一课块渲染：同色系容器（container 铺底 + 1dp 同色线 + 深色调正文），去掉左色条。
 *
 * 一条课块的纵向预算（小节高 46dp）：
 * 标题 12.5sp / 行高 15sp，教室 10sp，块底余量放「周次 · 教师」；
 * 正在上的课，块底换成「剩 X 分」，让「现在」这件事在色块里也成立。
 *
 * 交互（扁平无阴影）：按压 0.94 回弹；详情打开期间「弹起 + 深色描边 + 容器加深」，
 * 关闭动作一开始即快速回落（见 [Motion.SelectionExit]）与面板滑出并行完成。
 * 入场：随 tab 切页错峰淡入放大（[entranceDelayMs]，见 cascadeIn）。
 */
@Composable
fun CourseBlock(
    course: CourseEntity,
    preset: ThemePreset,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** 级联入场延迟（毫秒）；< 0 关闭，用于非课表网格场景 */
    entranceDelayMs: Long = -1L,
    /** 正在上的课剩余分钟数；null 表示不显示 */
    remainingMin: Int? = null,
    /** 网格节奏：系统字体放大时块会一起长高，行数按剩余空间收敛 */
    metrics: GridMetrics = GridMetrics.Default,
    onClick: () -> Unit,
) {
    val tone = remember(course.color, isDark) { courseTone(Color(course.color), isDark) }
    val shape = RoundedCornerShape(preset.radius.blockDp.dp)
    val span = (course.endSection - course.startSection + 1).coerceAtLeast(1)
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

    // 一节的块只放得下标题；两节起给教室，三节起给块底页脚。
    // 字体放大后行高跟着长，这里按真实可用高度收敛行数，任何倍率下都不裁字。
    val content = blockContentFor(span, metrics)
    val maxTitleLines = content.titleLines
    val showMeta = content.showRoom
    val showFooter = content.showFooter

    Column(
        modifier = modifier
            .zIndex(if (selected) 1f else 0f)
            .cascadeIn(entranceDelayMs)
            .graphicsLayer {
                val scale = press * (1f + 0.045f * lift)
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
            }
            .background(lerp(tone.container, tone.line, 0.45f * ring))
            .border(1.dp, tone.line, shape)
            .let {
                if (ring > 0.01f) it.border(2.dp, tone.ink.copy(alpha = ring * 0.85f), shape) else it
            }
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 3.dp),
    ) {
        Text(
            text = course.name,
            color = tone.ink,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (showMeta && course.room.isNotBlank()) {
            Text(
                text = course.room,
                color = tone.inkMuted,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        // 三节以上的高块竖着有富余，把教师补上（旧实现里这个字段从来没渲染过）
        if (content.showTeacher && course.teacher.isNotBlank()) {
            Text(
                text = course.teacher,
                color = tone.inkMuted,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showFooter) {
            // 页脚压到块底：高块的上端是标题、下端是周次，中间留白才像「留白」而不是「空」
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.BottomStart,
            ) {
                // 一行只放得下一件事：正在上的课说剩余时间，其余说周次。
                // 教师名放不下（58dp 块宽容不下两个 9.5sp 字符串），交给详情面板。
                val footer = when {
                    remainingMin != null -> "剩 $remainingMin 分"
                    course.weekMask.isNotBlank() -> WeekMask.toExpression(course.weekMask)
                    else -> ""
                }
                if (footer.isNotBlank()) {
                    Text(
                        text = footer,
                        color = if (remainingMin != null) tone.ink else tone.inkMuted,
                        fontSize = 9.5.sp,
                        lineHeight = 11.sp,
                        fontWeight = if (remainingMin != null) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
}
