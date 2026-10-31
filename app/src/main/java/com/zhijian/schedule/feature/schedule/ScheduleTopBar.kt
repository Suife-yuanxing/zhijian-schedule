package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.buttonFeedback

@Composable
internal fun ScheduleTopBar(
    ui: ScheduleUiState,
    pickerOpen: Boolean,
    onOpenWeekPicker: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val titleSource = remember { MutableInteractionSource() }
    // 面板开合时箭头翻转（弹出带一点过冲，呼应全局动效语言）
    val arrowAngle by animateFloatAsState(
        targetValue = if (pickerOpen) 180f else 0f,
        animationSpec = Motion.PopSpring,
        label = "weekArrow",
    )

    Row(
        Modifier
            .fillMaxWidth()
            // 真全面屏：顶栏内容避让状态栏，背景由页面 bg 自然铺到屏幕最顶端
            .statusBarsPadding()
            // end 与 start 同为 8dp：右侧那颗齿轮撤掉之后，标题的左右留白才对得上
            .padding(horizontal = 8.dp)
            .padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题即开关：点按「日期区间」展开周选择
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .buttonFeedback(titleSource, pressedScale = 0.98f)
                .clickable(
                    interactionSource = titleSource,
                    indication = LocalIndication.current,
                    onClick = onOpenWeekPicker,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            AnimatedContent(
                targetState = ui.selectedWeek,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (
                        slideInVertically(
                            animationSpec = tween(Motion.DUR_MEDIUM, easing = Motion.EaseOut),
                        ) { dir * it / 2 } + fadeIn(tween(Motion.DUR_MEDIUM, easing = Motion.EaseOut))
                    ) togetherWith
                        (
                            slideOutVertically(
                                animationSpec = tween(Motion.DUR_FAST),
                            ) { -dir * it / 2 } + fadeOut(tween(Motion.DUR_FAST))
                        )
                },
                label = "weekTitle",
            ) { week ->
                val start = ui.dateOf(week, 1)
                val end = ui.dateOf(week, 7)
                val subtitle =
                    when {
                        ui.currentWeek == null && ui.today.isBefore(ui.startDate) -> "第 $week 周 · 未开学"
                        ui.currentWeek == null -> "第 $week 周 · 学期已结束"
                        week == ui.currentWeek -> "本周 · 第 $week 周"
                        else -> "第 $week 周 · 非本周"
                    }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日",
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    // 日期数字用等宽，翻周时宽度不跳
                                    fontFeatureSettings = "tnum",
                                ),
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier =
                                Modifier
                                    .padding(start = 2.dp)
                                    .size(20.dp)
                                    .graphicsLayer { rotationZ = arrowAngle },
                        )
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}
