package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale
import java.time.LocalDate

/**
 * 一个周格在视觉上只有四种身份，其余组合（如「选中且是本周」）都归并到优先级最高的那个。
 *
 * 抽出这个枚举不是为了好看：四种身份各自对应背景、描边、周次字色、日期字色**四条**通道，
 * 以前是四条独立的 animate*AsState、各自持有一份目标值与时长。任何一处时长写岔
 * （例如描边 150ms 而底色 260ms），点下去就会先看到"描边已经没了、底色还在渐入"的半帧错位。
 * 现在四条通道挂在同一个 transition 上，只有一份进度，物理上不可能不同步。
 */
private enum class WeekCellVisual {
    /** 当前选中的周：实心强调色 + 反色文字 */
    SELECTED,

    /** 本周但未选中：浅色底 + 主色描边 */
    CURRENT,

    /** 已经过去的周：整格淡化，视觉上退到背景里 */
    PAST,

    /** 普通周 */
    NORMAL,
}

/**
 * 周格：选中实心弹跳、本周主色底+描边、过去淡化（与日详情速选 pill 同一语言）。
 *
 * 动效归属（对照 wnb-compose-animation 的闸门）：这里三条通道各有各的信息，
 * 都不是装饰——底色与字色表达"选没选中"，描边表达"是不是本周"，缩放表达"点中了这一格"。
 * 缩掉任何一条，用户就得靠读日期才能确认自己点了哪一周。
 */
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
    val visual =
        when {
            selected -> WeekCellVisual.SELECTED
            isCurrentWeek -> WeekCellVisual.CURRENT
            past -> WeekCellVisual.PAST
            else -> WeekCellVisual.NORMAL
        }
    // 单一 transition 驱动全部颜色通道：所有通道共用同一个 durationMillis，
    // 于是"底色、描边、周次、日期"永远同进同退，不会出现半帧错配的配色。
    val transition = updateTransition(visual, label = "weekCell")
    val background by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellBg",
    ) { target -> weekCellBackground(target, colors) }
    val ring by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellRing",
    ) { target -> weekCellRing(target, colors) }
    val titleColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellTitle",
    ) { target -> weekCellTitle(target, colors) }
    val dateColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellDate",
    ) { target -> weekCellDate(target, colors) }
    // 缩放走弹簧：它是"点中了"的触觉替代品，需要过冲；颜色是状态迁移，需要可预测的匀速。
    // 两条时钟同时跑是刻意的——同一件事的两种表达（物理反馈 vs 状态迁移），不是两套动画。
    val popScale by transition.animateFloat(
        transitionSpec = { Motion.PopSpring },
        label = "weekCellPop",
    ) { target -> if (target == WeekCellVisual.SELECTED) SELECTED_POP_SCALE else 1f }
    val shape = RoundedCornerShape(13.dp)

    Column(
        modifier
            .pressScale(pressSource, pressedScale = 0.94f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }.clip(shape)
            .background(background)
            .border(1.5.dp, ring, shape)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(vertical = 10.dp),
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

/** 选中态放大比例：1.06 肉眼可辨但不会把相邻格子挤开 */
private const val SELECTED_POP_SCALE = 1.06f

// ---------- 四种视觉身份 → 四条通道的取值 ----------

private fun weekCellBackground(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.accent
        WeekCellVisual.CURRENT -> colors.accent.copy(alpha = CURRENT_TINT_ALPHA)
        WeekCellVisual.PAST, WeekCellVisual.NORMAL -> colors.surfaceVariant
    }

/** 未选中的方块保留一丝细描边，块面更挺括；本周用主色描边提亮 */
private fun weekCellRing(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> Color.Transparent
        WeekCellVisual.CURRENT -> colors.accent
        WeekCellVisual.PAST, WeekCellVisual.NORMAL -> colors.border.copy(alpha = IDLE_RING_ALPHA)
    }

private fun weekCellTitle(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.onAccent
        WeekCellVisual.CURRENT -> colors.accent
        WeekCellVisual.PAST -> colors.textMuted
        WeekCellVisual.NORMAL -> colors.text
    }

private fun weekCellDate(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.onAccent.copy(alpha = SELECTED_DATE_ALPHA)
        WeekCellVisual.CURRENT -> colors.accent.copy(alpha = CURRENT_DATE_ALPHA)
        WeekCellVisual.PAST -> colors.textMuted.copy(alpha = PAST_DATE_ALPHA)
        WeekCellVisual.NORMAL -> colors.textMuted
    }

/** 本周格的浅色底：再淡一点就与普通格分不出来，再浓一点会抢走选中格的实心感 */
private const val CURRENT_TINT_ALPHA = 0.12f

/** 未选中描边的不透明度：只用来"收边"，不该读成一条线 */
private const val IDLE_RING_ALPHA = 0.55f

/** 选中态日期字压在实心底色上，略降一点避免与周次抢同一档权重 */
private const val SELECTED_DATE_ALPHA = 0.92f

/** 本周态日期字跟着主色走，但要比周次淡 */
private const val CURRENT_DATE_ALPHA = 0.8f

/** 过去态的日期字再退一层：这一段是"可回看但不重要"的信息 */
private const val PAST_DATE_ALPHA = 0.7f

/** 「10.12-18」；跨月时补月份「10.30-11.5」 */
private fun weekRangeText(
    start: LocalDate,
    end: LocalDate,
): String =
    if (start.monthValue == end.monthValue) {
        "${start.monthValue}.${start.dayOfMonth}-${end.dayOfMonth}"
    } else {
        "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
    }
