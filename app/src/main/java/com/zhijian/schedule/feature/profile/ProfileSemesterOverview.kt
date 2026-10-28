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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.CardDepthLadder
import com.zhijian.schedule.core.ui.cardTextDepth
import com.zhijian.schedule.core.ui.cardTilt
import com.zhijian.schedule.core.ui.cardTiltGlare
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.reliefShadow
import com.zhijian.schedule.core.ui.rememberCardTiltState
import com.zhijian.schedule.core.ui.rememberFocusCardTiltStyle
import com.zhijian.schedule.core.ui.rememberMotionAllowed
import java.util.Locale

/**
 * 「我的」页顶部的学期概览大卡：周次进度 + 课程数 + 学分/绩点。
 *
 * 卡面文字是按 [CardDepthLadder] 排开的一条深度阶梯：周次数字最靠前、卡名次之、
 * 学期胶囊再靠后、量纲说明最浅，事实类信息（开学日期 / 进度条 / 页码）留在卡面 Z=0。
 * 每层吃两道表现——倾动时的视差位移（[cardTextDepth]）与静止时的浮雕软影
 * （[reliefShadow]），于是按住拖动时字与字之间会真的错开，松手回正后仍读得出前后。
 *
 * 卡片右下角的绩点块是共享元素锚点，点按直接进成绩页；它不参与视差（位移会让转场起点偏），
 * 但同样吃一道浮雕，与左侧数字错开半档，读作同一张卡上的两件物体。
 */
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
    val fraction =
        if (week != null && timetable.totalWeeks > 0) {
            week.toFloat() / timetable.totalWeeks
        } else {
            0f
        }
    val stats = "${timetable.startDate} 开学 · $courseCount 门课程"
    val density = LocalDensity.current
    val dark = LocalIsDark.current
    // 每层文字的浮雕投影色：同色相深调，纯黑压在彩色字上会发灰（见 CourseTone.shade 的注释）
    val inkShadow = lerp(colors.text, Color.Black, 0.45f)
    val accentShadow = lerp(colors.accent, Color.Black, 0.45f)
    // 3D 字形的明暗面：上缘受光、下缘背光。与浮雕投影是两个通道——影子给出"离卡面多高"，
    // 渐变笔芯给出"这块字本身有多厚"，只有影子没有明暗面时，大字仍是"一张被照亮的贴纸"。
    val heroInk =
        Brush.verticalGradient(
            0f to lerp(colors.accent, Color.White, if (dark) 0.30f else 0.20f),
            0.55f to colors.accent,
            1f to lerp(colors.accent, Color.Black, if (dark) 0.22f else 0.16f),
        )
    val gpaInk =
        Brush.verticalGradient(
            0f to lerp(colors.text, Color.White, if (dark) 0.42f else 0f),
            0.6f to colors.text,
            1f to lerp(colors.text, colors.accent, if (dark) 0.22f else 0.14f),
        )
    // 四段文字各走各的 Z 平面（6 / 4 / 3 / 1.5dp）：倾动时彼此错开才读得出「层」。
    // 此前整列共用一个 12dp，所有字同进同退，等于没有深度。
    val titleDepth = Modifier.cardTextDepth(state = tilt, style = tiltStyle, depth = CardDepthLadder.Title)
    val chipDepth = Modifier.cardTextDepth(state = tilt, style = tiltStyle, depth = CardDepthLadder.Chip)
    val heroDepth = Modifier.cardTextDepth(state = tilt, style = tiltStyle, depth = CardDepthLadder.Hero)
    val unitDepth = Modifier.cardTextDepth(state = tilt, style = tiltStyle, depth = CardDepthLadder.Unit)
    // 静态浮雕：系统关闭动画 / 截图 / 静态浏览时位移恒为 0，深度只能靠这道软影表达
    val heroShadow = CardDepthLadder.Hero.reliefShadow(accentShadow, density, dark)
    val titleShadow = CardDepthLadder.Title.reliefShadow(inkShadow, density, dark)
    val unitShadow = CardDepthLadder.Unit.reliefShadow(inkShadow, density, dark)
    // 焦点卡：更松的圆角 + 极轻的暖色渐变，和下方分组列表拉开层级
    val heroBrush =
        Brush.linearGradient(
            colors = listOf(colors.surface, lerp(colors.surface, colors.accent, ACCENT_SURFACE_ALPHA)),
        )
    // 焦点卡跟着指腹做透视倾动：卡片够大，倾角比课块更收敛，反光落在卡面而非文字上
    val cardModifier =
        Modifier
            .fillMaxWidth()
            .cardTilt(state = tilt, shape = shape, style = tiltStyle, enabled = motionAllowed)
            .clip(shape)
            .background(heroBrush)
            .cardTiltGlare(
                state = tilt,
                shape = shape,
                style = tiltStyle,
                // 暗面掺主题强调色而不是纯黑：这张卡是"暖白纸"，纯黑压边会发灰发脏
                shade = lerp(Color.Black, colors.accent, 0.35f),
            ).padding(20.dp)
    Column(cardModifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timetable.name,
                        style = MaterialTheme.typography.titleMedium.copy(shadow = titleShadow),
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = titleDepth,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timetable.semester.ifBlank { "未设置学期" },
                        color = colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        // 贴纸自带底色：位移示层级即可，不叠浮雕（会和底色糊成双边框）
                        modifier =
                            chipDepth
                                .clip(RoundedCornerShape(7.dp))
                                .background(colors.accent.copy(alpha = ACCENT_TILE_ALPHA))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = week?.let { "第 $it 周" } ?: "学期外",
                        // 颜色交给渐变笔芯，别再传 color（两者同时给以 brush 为准，
                        // 留着一个无效的 color 会让后来读代码的人以为改它有用）
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(brush = heroInk, shadow = heroShadow),
                        modifier = heroDepth,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "共 ${timetable.totalWeeks} 周",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        style = TextStyle(shadow = unitShadow),
                        modifier = unitDepth.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                // 事实类信息留在卡面（Z = 0）：倾斜 / 抬起的平面上长文本会亚像素发虚，
                // 深度只给短的高权重文字（见 CardDepth 的 kdoc）
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
                    valueShadow = titleShadow,
                    valueInk = gpaInk,
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
                modifier =
                    Modifier
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
private fun GpaTile(
    weightedGpa: Double?,
    totalCredits: Double,
    valueShadow: Shadow?,
    valueInk: Brush,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    // 绩点块是卡面上的「第二层贴纸」：浮岛上的那枚数字同样吃一道浮雕与明暗面，
    // 与左侧 6dp 的周次数字错开一档，读作同一张卡上的两件物体
    val tileModifier =
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
            ).padding(horizontal = 12.dp, vertical = 10.dp)
    Column(tileModifier, horizontalAlignment = Alignment.End) {
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
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(brush = valueInk, shadow = valueShadow),
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
