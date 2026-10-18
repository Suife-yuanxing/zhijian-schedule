package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 一套风格内的完整色彩 Token。
 */
data class CourseColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
)

/**
 * 课块渲染样式：透明填充 + 左色条（一套渲染规则适配全部风格）。
 */
data class BlockStyle(
    val fillAlphaLight: Float,
    val fillAlphaDark: Float,
    val barWidthDp: Float,
)

data class TypePreset(
    val serifTitle: Boolean,
)

data class RadiusPreset(
    val blockDp: Float,
    val cardDp: Float,
    val sheetDp: Float,
)

/**
 * 清水玻璃材质参数（仅「液态玻璃」预设非空）：高透明主体 + 流动光带 + 边缘高光。
 */
data class GlassSpec(
    /** 玻璃底色（亮/暗）：水一样通透的浅着色 */
    val tintLight: Color,
    val tintDark: Color,
    /** 玻璃主体 alpha（亮/暗），越大越不透明 */
    val bodyAlphaLight: Float,
    val bodyAlphaDark: Float,
    /** 选中液滴填充浓度（亮/暗）：越高越接近实心 accent，与浮岛水膜的对比越强 */
    val chipAlphaLight: Float,
    val chipAlphaDark: Float,
    /** 边缘高光强度 */
    val specular: Float,
    /** 流动光带强度（0~1） */
    val flow: Float,
    /** 边缘透镜位移强度（相对浮岛高度的比例，0 = 无折射） */
    val lens: Float,
    /** 边缘镜像反射混合强度（0~1）：贴边区域把周围背景「收」进反光的程度 */
    val mirror: Float,
    /** 倾角镜面高光强度（0~1）：反光随重力方向滑动的程度 */
    val tiltSpec: Float,
)

/**
 * 设计 Token 体系：一套代码 + N 套风格（方案 5.2 ①）。
 */
data class ThemePreset(
    val id: String,
    val name: String,
    val light: CourseColors,
    val dark: CourseColors,
    val coursePalette: List<Color>,
    val blockStyle: BlockStyle,
    val typography: TypePreset,
    val radius: RadiusPreset,
    val isDynamic: Boolean = false,
    /** 非空 = 启用玻璃材质渲染（仅 glass 预设） */
    val glass: GlassSpec? = null,
)

object ThemeIds {
    const val PAPER = "paper"
    const val DYNAMIC = "dynamic"
    const val MINIMAL = "minimal"
    const val AMOLED = "amoled"
    const val CANDY = "candy"
    const val GLASS = "glass"
}

/**
 * 网格语言（与主题皮肤正交：任何一套风格都能选任意一种网格画法）。
 *
 * - [EDITORIAL] 编辑式：上午/下午/晚上三段标题 + 间距分层，段内只留一条 hairline；
 * - [BLOCK] 色块制：去掉全部网格线，用时段底纹条与色块自身分层。
 */
object GridStyles {
    const val EDITORIAL = "editorial"
    const val BLOCK = "block"

    val all: List<Pair<String, String>> = listOf(EDITORIAL to "编辑式", BLOCK to "色块制")
}
