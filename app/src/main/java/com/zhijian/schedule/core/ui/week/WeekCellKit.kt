// 本文件是周视图单元格的成套件：解析函数 + 主 composable + 若干私有绘制件。
// 按文件名概括这套主题比按单个声明命名更准确，故显式放行 MatchingDeclarationName。
@file:Suppress("MatchingDeclarationName")

package com.zhijian.schedule.core.ui.week

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale

/**
 * 周格视觉身份：全 App 三处周次选择的统一语言。
 * 选中 = 实心强调色 + 弹跳；本周 = 浅底 + 下划线；过去 = 淡化；普通 = 发丝描边。
 * 五条颜色通道挂在同一个 transition 上（一份进度，物理上不可能不同步）。
 */
enum class WeekCellVisual {
    /** 当前选中的周：实心强调色 + 反色文字 */
    SELECTED,

    /** 本周但未选中：浅色底 + 主色描边 */
    CURRENT,

    /** 已经过去的周：整格淡化，视觉上退到背景里 */
    PAST,

    /** 普通周 */
    NORMAL,
}

/** 优先级归并：选中 > 本周 > 过去 > 普通（与旧 WeekCell 的 when 归并一致） */
fun resolveWeekCellVisual(
    selected: Boolean,
    current: Boolean,
    past: Boolean,
): WeekCellVisual =
    when {
        selected -> WeekCellVisual.SELECTED
        current -> WeekCellVisual.CURRENT
        past -> WeekCellVisual.PAST
        else -> WeekCellVisual.NORMAL
    }

/**
 * 周格：选中实心弹跳、本周主色底+描边、过去淡化（与日详情速选 pill 同一语言）。
 *
 * 动效归属（对照 wnb-compose-animation 的闸门）：这里三条通道各有各的信息，
 * 都不是装饰——底色与字色表达"选没选中"，描边表达"是不是本周"，缩放表达"点中了这一格"。
 * 缩掉任何一条，用户就得靠读日期才能确认自己点了哪一周。
 *
 * @param title 主文案（「第 3 周」或紧凑格的「3」）
 * @param subtitle 副文案（日期范围）；null 时渲染为紧凑数字格（课程编辑器用）
 * @param occupied 这一周已有别的课占用：右下角点一个小圆点。
 *   这是周次选择里唯一无法从「选没选中」推出来的信息——20 个同质方块
 *   之所以读起来是噪音，正是因为缺了它。
 * @param isCurrent 这一周是本周：底边一条下划线。与选中态解耦——
 *   选中是用户的选择，本周是日历的事实，两条信息各走各的通道。
 */
@Composable
fun WeekCell(
    title: String,
    subtitle: String?,
    visual: WeekCellVisual,
    modifier: Modifier = Modifier,
    occupied: Boolean = false,
    isCurrent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    // 单一 transition 驱动全部颜色通道：所有通道共用同一个 durationMillis，
    // 于是"底色、描边、周次、日期"永远同进同退，不会出现半帧错配的配色。
    val transition = updateTransition(visual, label = "weekCell")
    val background by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellBg",
    ) { target -> weekCellBackground(target, colors) }
    // 描边是结构通道：未选中的格必须有边界，否则格阵里 20 个未选格读作悬浮数字，
    // 可点范围看不见。选中格是实心填充，描边只会往色块上叠线，给透明。
    val borderColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellBorder",
    ) { target -> weekCellBorder(target, colors) }
    // 本周的下划线走单独的通道，且只跟随 isCurrent：选中态已经由实心填充表达了，
    // 若每个选中格都自动带线，格阵里十几个选中块就是十条重复的白线。
    // 下划线是形状通道，不依赖色觉，也是扁平化下唯一还读得出来的'本周'标记。
    val underlineColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellUnderline",
    ) { target -> weekCellUnderline(target, isCurrent, colors) }
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
    val shape = RoundedCornerShape(FLAT_CORNER_RADIUS)

    // 外层 Box 只为承载右下角的占用圆点：它必须浮在格子底色之上，
    // 又不能参与 Column 的垂直排布（否则格子高度会随圆点变大，选中/未选中高度不一致）
    Box(
        modifier
            .pressScale(pressSource, pressedScale = 0.94f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }.clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .drawBehind {
                if (underlineColor.alpha > 0f) {
                    val inset = size.width * UNDERLINE_INSET_RATIO
                    val stroke = UNDERLINE_STROKE.toPx()
                    val y = size.height - stroke / 2f
                    drawLine(
                        color = underlineColor,
                        start = Offset(inset, y),
                        end = Offset(size.width - inset, y),
                        strokeWidth = stroke,
                    )
                }
            }.then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = if (subtitle == null) 9.dp else 10.dp,
                    horizontal = 2.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                fontSize = if (subtitle == null) 13.sp else 13.5.sp,
                // 字重是扁平化下的第二层级通道：选中 SemiBold、未选中 Medium。
                // 不靠底色差也能读出"哪几个被选了"。
                fontWeight =
                    if (visual == WeekCellVisual.SELECTED) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                    color = dateColor,
                )
            }
        }
        if (occupied) OccupiedDot(selected = visual == WeekCellVisual.SELECTED)
    }
}

/**
 * 占用点：这一周已经有别的课。
 *
 * 抽成独立 composable 而不是内联在 WeekCell 里：WeekCell 已经到了 detekt
 * LongMethod 的 100 行门槛，而圆点本身有独立的语义（唯一无法从"选没选中"
 * 推导出来的信息），单独命名比内联更好读。
 */
@Composable
private fun BoxScope.OccupiedDot(selected: Boolean) {
    val colors = LocalCourseColors.current
    val dotColor =
        if (selected) {
            colors.onAccent.copy(alpha = OCCUPIED_DOT_ON_SELECTED_ALPHA)
        } else {
            colors.textMuted.copy(alpha = OCCUPIED_DOT_ALPHA)
        }
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 5.dp, bottom = 5.dp)
            .size(OCCUPIED_DOT_SIZE)
            .clip(CircleShape)
            .background(dotColor),
    )
}

/** 扁平化圆角：8dp。13dp 会把小格子读成"药丸"，也让描边与圆角互相抢注意力 */
private val FLAT_CORNER_RADIUS = 8.dp

/** 下划线两端内缩比例：1/8，看起来像"文字下面一道线"而不是"格子底边描边" */
private const val UNDERLINE_INSET_RATIO = 0.125f

/** 下划线粗细：2dp（在 DrawScope 里换算成像素） */
private val UNDERLINE_STROKE = 2.dp

/** 选中态放大比例：1.06 肉眼可辨但不会把相邻格子挤开 */
private const val SELECTED_POP_SCALE = 1.06f

/** 占用圆点直径：再小就与描边糊在一起，再大就抢主文案的权重 */
private val OCCUPIED_DOT_SIZE = 3.dp

/**
 * 未选中态的占用点。
 *
 * 原来 α0.45 在纸本主题下合成后只有 1.60:1，低于非文本图形 3:1 的下限（UX 实测）。
 * 扁平化把底色去掉之后更没依托，所以改成不透明：圆点仍是"点"通道，
 * 但必须真的看得见，否则这条信息等于不存在。
 */
private const val OCCUPIED_DOT_ALPHA = 1.0f

/** 选中态压在实心强调色上：反色并压低不透明度，避免与白字抢焦点 */
private const val OCCUPIED_DOT_ON_SELECTED_ALPHA = 0.7f

// ---------- 四种视觉身份 → 五条通道的取值 ----------

private fun weekCellBackground(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.accent
        WeekCellVisual.CURRENT -> colors.accent.copy(alpha = CURRENT_TINT_ALPHA)
        // 未选中不铺底色：扁平化要的是"数字浮在页面上"，
        // 20 个 surfaceVariant 方块排在一起会把整块区域读成一张表格
        WeekCellVisual.PAST, WeekCellVisual.NORMAL -> Color.Transparent
    }

/**
 * 本周 = 底边一条线，只跟随 [isCurrent]。
 *
 * 扁平化之后不能再靠"浅底 + 描边"表达本周：两个色值差在纸本主题下只有 2.66:1，
 * 低于非文本图形 3:1 的下限（UX 实测）。换成形状通道后，即使色觉障碍也能读出来。
 * 本周格同时被选中时，线改用 onAccent，避免压在实心强调色上消失。
 */
private fun weekCellUnderline(
    visual: WeekCellVisual,
    isCurrent: Boolean,
    colors: CourseColors,
): Color =
    when {
        !isCurrent -> Color.Transparent
        visual == WeekCellVisual.SELECTED -> colors.onAccent.copy(alpha = SELECTED_UNDERLINE_ALPHA)
        else -> colors.accent
    }

/**
 * 未选中态的发丝描边：格阵的结构线。
 *
 * 过去的周整格淡化，描边跟着退一档；选中格实心填充自带边界，描边给透明。
 */
private fun weekCellBorder(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> Color.Transparent
        WeekCellVisual.PAST -> colors.border.copy(alpha = PAST_BORDER_ALPHA)
        WeekCellVisual.CURRENT, WeekCellVisual.NORMAL -> colors.border
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

/** 选中格上的本周下划线：留下但不抢白字，读作"顺带告诉你这周是本周" */
private const val SELECTED_UNDERLINE_ALPHA = 0.85f

/** 选中态日期字压在实心底色上，略降一点避免与周次抢同一档权重 */
private const val SELECTED_DATE_ALPHA = 0.92f

/** 过去周的发丝描边淡化档：边界仍在，但视觉上退到背景里 */
private const val PAST_BORDER_ALPHA = 0.55f

/** 本周态日期字跟着主色走，但要比周次淡 */
private const val CURRENT_DATE_ALPHA = 0.8f

/** 过去态的日期字再退一层：这一段是"可回看但不重要"的信息 */
private const val PAST_DATE_ALPHA = 0.7f
