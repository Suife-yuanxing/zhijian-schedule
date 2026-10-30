package com.zhijian.schedule.core.ui.metal

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.zhijian.schedule.core.theme.LocalCoursePreset

/**
 * 卡面背景的统一入口（水银镜面主题专用）。
 *
 * 为什么需要它：项目里卡片的画法散落在十几个文件（background(colors.surface, RoundedCornerShape(...))
 * 到处都是）。金属主题不是「换个颜色」，而是要把那块矩形换成一块金属板——有倒角、有球冠明暗、
 * 有拉丝。如果每处各写一遍 metalSurface，参数必然跑偏；集中到这里之后，
 * 「页面上的卡片」共享同一份材质语义，改一处全站生效。
 *
 * 抛光程度按卡的语义分档：
 * - [CardTone.SURFACE]  普通内容卡：哑光为主（chrome 0.18），像阳极氧化铝，不抢内容；
 * - [CardTone.RAISED]   浮起来的焦点卡（学期概览、正在上课）：抛光到 0.42，它会反光；
 * - [CardTone.INSET]    内嵌块（输入框底、标签底）：几乎全哑光（0.10），读作「凹进去的金属」。
 *
 * 非金属主题下逐字节退回原来的 background——其余 6 套主题的卡片一个像素都不变。
 */
enum class CardTone {
    /** 普通内容卡：哑光阳极氧化铝 */
    SURFACE,

    /** 浮起的焦点卡：抛光到会反光 */
    RAISED,

    /** 内嵌块：几乎全哑光，读作凹面 */
    INSET,
}

/**
 * @param shape 卡片圆角（必须与调用点的 clip 一致，否则倒角圈会露在形状外）
 * @param color 非金属主题下的底色（金属主题下作为 F0 底面色的参考）
 * @param tone 材质档位
 */
@Composable
fun Modifier.metalCard(
    shape: RoundedCornerShape,
    color: Color,
    tone: CardTone = CardTone.SURFACE,
): Modifier {
    val metal = LocalCoursePreset.current.metal
    if (metal == null) return this.background(color, shape)
    val chrome =
        when (tone) {
            CardTone.SURFACE -> 0.18f
            CardTone.RAISED -> 0.42f
            CardTone.INSET -> 0.10f
        }
    return this
        .background(color, shape)
        .metalSurface(
            shape = shape,
            clock = LocalMetalClock.current,
            chromeOverride = chrome,
            faceOverride = color,
        )
}

/** 带 dp 圆角的便捷重载（多数调用点是临时构造 RoundedCornerShape） */
@Composable
fun Modifier.metalCard(
    radiusDp: Dp,
    color: Color,
    tone: CardTone = CardTone.SURFACE,
): Modifier = metalCard(RoundedCornerShape(radiusDp), color, tone)
