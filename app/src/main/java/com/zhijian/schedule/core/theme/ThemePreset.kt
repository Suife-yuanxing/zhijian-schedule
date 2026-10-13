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
)

object ThemeIds {
    const val PAPER = "paper"
    const val DYNAMIC = "dynamic"
    const val MINIMAL = "minimal"
    const val AMOLED = "amoled"
    const val CANDY = "candy"
}
