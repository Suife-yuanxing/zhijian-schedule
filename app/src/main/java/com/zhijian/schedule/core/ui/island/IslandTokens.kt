package com.zhijian.schedule.core.ui.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.GlassSpec
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/**
 * 浮岛专用配色 Token。
 *
 * 与 [CourseColors] 的关系：浮岛是**悬浮层**，压的底比正文更亮（surface / 玻璃底层），
 * 直接借用页面 token 会掉档（实测 11 组里 7 组低于 WCAG AA）。所以这里独立求解，
 * 只作用于浮岛，不污染正文。
 */
data class IslandPalette(
    /** 未选中图标与标签：由 textMuted 向 text 混到刚好 ≥4.5 为止 */
    val muted: Color,
    /** 选中图标与标签：玻璃主题取 onAccent，其余取 text */
    val selected: Color,
    /** 选中胶囊 / 液滴的底色（已按对比度压深或提亮） */
    val chip: Color,
    /** 选中胶囊 / 液滴的填充浓度 */
    val chipAlpha: Float,
    /** 求解时假定的底层（用于单测复算） */
    val under: Color,
    /**
     * 胶囊描边浓度：淡胶囊与浮岛底同色系时边界会消失，靠这条 hairline 定义轮廓。
     * 玻璃档本体已由折射彩边定义边界，不需要再描（返回 0）。
     */
    val chipEdgeAlpha: Float,
)

object IslandTokens {

    /**
     * 非玻璃主题的选中胶囊填充浓度。
     *
     * 为什么不提到「一眼可见」的 0.39~0.49：M3 官方指示器（#E8DEF8 压 #F3EDF7）
     * 与容器的对比也只有 1.13:1，说明淡色指示器是业界共识——它是背景，不是主体。
     * 把浓度提上去会把每屏常驻的导航件变成色块，抢内容。可见度改由 [chipEdgeAlpha]
     * 的描边解决，而不是靠填充加压。
     */
    const val CHIP_ALPHA = 0.13f

    /** 非玻璃主题的胶囊描边浓度：够定义边界、又不会读成「框住了一块色」 */
    const val CHIP_EDGE_ALPHA = 0.42f

    /**
     * 填充的纵向渐变系数：上缘更透、下缘更实（光从上面来）。
     *
     * 取 0.77 / 1.23，中点正好回到 1.0 —— 文字与图标压在胶囊中段，实测浓度仍是
     * [CHIP_ALPHA]，所以 [IslandContrastTest] 里按单一浓度复算的对比度结论继续成立。
     * 幅度刻意小：这是给淡色指示器加一层体积，不是把它变成色块。
     */
    const val CHIP_FILL_TOP = 0.77f
    const val CHIP_FILL_BOTTOM = 1.23f

    /**
     * 描边的纵向渐变系数：上缘受光更强、下缘收柔。
     *
     * 均值 ≈ 1.0，守住 [chipEdgeAlpha]「刚好定义边界」的浓度前提；只是把均匀的一圈
     * 拆成「顶亮底柔」，读起来是一颗有厚度的水滴，而不是贴上去的一个描边框。
     */
    const val CHIP_EDGE_TOP = 1.24f
    const val CHIP_EDGE_BOTTOM = 0.71f

    /**
     * 果冻拉伸：宽度的最大增幅（相对胶囊自身，不是绝对 dp）。
     *
     * 为什么是比例：胶囊宽度随 tab 数与屏宽变，固定 dp 会让三 tab 手机上拉过头、
     * 平板上几乎看不出。+16% 时 88dp 胶囊长到 102dp，还压得住一格。
     */
    const val PILL_GROW = 0.16f

    /** 果冻拉伸：高度的最大压缩。宽了就矮，太多会读成扁条，太少看不出被拉长 */
    const val PILL_SQUASH = 0.11f

    /** 拉伸量分给「前缘」的比例：鼻尖先出去、尾巴拖在后面，才读作被拽着走 */
    const val PILL_LEAD = 0.62f

    /** 正文与图形共用的门槛：11sp 标签按 WCAG AA 正文算 */
    const val MIN_TEXT_CONTRAST = 4.5

    /** 图标在收敛态的最小门槛（图形 3.0），单测用来兜住「收起来也别看不见」 */
    const val MIN_ICON_CONTRAST = 3.0

    /**
     * 求解一套浮岛配色。
     *
     * [under] 取法：玻璃档本体近清水（bodyAlpha 0.16~0.20），透出的就是页面底色 bg；
     * 其余档是不透明 surface。取错会让求解偏松或偏紧，所以按档位分别取。
     */
    fun solve(colors: CourseColors, glass: GlassSpec?, isDark: Boolean): IslandPalette {
        val under = if (glass != null) colors.bg else colors.surface
        val muted = Contrast.mixUntil(colors.textMuted, colors.text, under, MIN_TEXT_CONTRAST)
        val chipAlpha = glass?.let { if (isDark) it.chipAlphaDark else it.chipAlphaLight } ?: 0.13f
        // 玻璃档：选中文字压在饱和液滴上（白字），液滴必须压深才够；
        // 非玻璃档：胶囊只有 13% 淡彩，文字用 text，本来就 10:1 以上，这里同样跑一遍兜底。
        val chip = Contrast.adjustUntil(colors.accent, under, colors.onAccent, chipAlpha, MIN_TEXT_CONTRAST)
        return IslandPalette(
            muted = muted,
            selected = if (glass != null) colors.onAccent else colors.text,
            chip = chip,
            chipAlpha = chipAlpha,
            under = under,
            chipEdgeAlpha = if (glass != null) 0f else CHIP_EDGE_ALPHA,
        )
    }
}

/** 当前主题下的浮岛配色（主题 / 亮暗 / 玻璃档变化时重新求解） */
@Composable
fun rememberIslandPalette(): IslandPalette {
    val colors = LocalCourseColors.current
    val glass = LocalCoursePreset.current.glass
    val isDark = LocalIsDark.current
    return remember(colors, glass, isDark) { IslandTokens.solve(colors, glass, isDark) }
}
