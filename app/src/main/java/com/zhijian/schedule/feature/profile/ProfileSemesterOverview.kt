package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.cardTilt
import com.zhijian.schedule.core.ui.cardTiltGlare
import com.zhijian.schedule.core.ui.cardTiltParallax
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.rememberCardTiltState
import com.zhijian.schedule.core.ui.rememberFocusCardTiltStyle
import com.zhijian.schedule.core.ui.rememberMotionAllowed
import java.util.Locale

/**
 * 「我的」页顶部的学期概览大卡：周次进度 + 课程数 + 学分/绩点。
 * 卡片右下角的绩点块是共享元素锚点，点按直接进成绩页。
 */

/** 学期概览：周次进度 + 课程数 + 学分/绩点，GPA 区可直接进入成绩页 */
@Composable
internal fun SemesterOverviewCard(
    timetable: TimetableEntity,
    week: Int?,
    courseCount: Int,
    totalCredits: Double,
    weightedGpa: Double?,
    entryTransitions: ProfileEntryTransitions,
    onOpenGrades: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val shape = RoundedCornerShape(22.dp)
    val tilt = rememberCardTiltState()
    // 系统「关闭动画」时整块倾动/反光/抬升都停用（WCAG 2.3.3、tvOS ACCESS-04）
    val motionAllowed = rememberMotionAllowed()
    // 焦点卡面积大，倾角比课块多 2°、反光再提一档，才看得出「玻璃卡片被指腹压下去」
    // （与今日页「正在上课」大卡共用同一份取值，见 rememberFocusCardTiltStyle）
    val tiltStyle = rememberFocusCardTiltStyle()
    val fraction = if (week != null && timetable.totalWeeks > 0) {
        week.toFloat() / timetable.totalWeeks
    } else 0f
    val stats = "${timetable.startDate} 开学 · $courseCount 门课程"
    // 焦点卡：更松的圆角 + 极轻的暖色渐变，和下方分组列表拉开层级
    val heroBrush = Brush.linearGradient(
        colors = listOf(colors.surface, lerp(colors.surface, colors.accent, ACCENT_SURFACE_ALPHA)),
    )
    Column(
        Modifier
            .fillMaxWidth()
            // 焦点卡跟着指腹做透视倾动：卡片够大，倾角比课块更收敛，反光落在卡面而非文字上
            .cardTilt(state = tilt, shape = shape, style = tiltStyle, enabled = motionAllowed)
            .clip(shape)
            .background(heroBrush)
            .cardTiltGlare(
                state = tilt,
                shape = shape,
                style = tiltStyle,
                // 暗面掺主题强调色而不是纯黑：这张卡是"暖白纸"，纯黑压边会发灰发脏
                shade = lerp(Color.Black, colors.accent, 0.35f),
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 前景文字按 Apple 图层位移表动得最多；GpaTile 是共享元素锚点，不参与视差
            Column(
                Modifier
                    .weight(1f)
                    .cardTiltParallax(state = tilt, style = tiltStyle, depth = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timetable.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timetable.semester.ifBlank { "未设置学期" },
                        color = colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(colors.accent.copy(alpha = ACCENT_TILE_ALPHA))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = week?.let { "第 $it 周" } ?: "学期外",
                        color = colors.accent,
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "共 ${timetable.totalWeeks} 周",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stats,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            ProfileEntryAnchor(
                key = ProfileEntryKeys.GRADES_GPA,
                transitions = entryTransitions,
            ) {
                GpaTile(
                    weightedGpa = weightedGpa,
                    totalCredits = totalCredits,
                    onClick = onOpenGrades,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp)),
                color = colors.accent,
                trackColor = colors.border.copy(alpha = 0.35f),
            )
            // 周刻度：在 1/4、1/2、3/4 处留一道极淡的刻度，进度不再是一根光秃的横条
            Canvas(Modifier.fillMaxSize()) {
                val tickColor = colors.textMuted.copy(alpha = 0.22f)
                val half = 2.5.dp.toPx()
                val stroke = 1.dp.toPx()
                listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                    val x = size.width * f
                    drawLine(
                        color = tickColor,
                        start = Offset(x, size.height / 2f - half),
                        end = Offset(x, size.height / 2f + half),
                        strokeWidth = stroke,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row {
            Text("学期进度", color = colors.textMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = week?.let { "第 $it / ${timetable.totalWeeks} 周" } ?: "不在学期内",
                color = colors.textMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GpaTile(weightedGpa: Double?, totalCredits: Double, onClick: () -> Unit) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .pressScale(source, pressedScale = 0.94f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accent.copy(alpha = ACCENT_TILE_ALPHA))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = {
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("加权绩点", color = colors.textMuted, fontSize = 10.sp)
            Spacer(Modifier.width(1.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = weightedGpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (totalCredits > 0) {
            Text(
                text = "${String.format(Locale.US, "%.1f", totalCredits)} 学分",
                color = colors.textMuted,
                fontSize = 10.sp,
            )
        }
    }
}
