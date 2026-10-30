package com.zhijian.schedule.core.ui.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.MetalSpec

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

    /** 金属档选中胶囊的不透明度：金属珠是不透的（不透明度只影响与片底的融合边缘） */
    const val METAL_CHIP_ALPHA = 1f

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
    fun solve(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec? = null,
    ): IslandPalette {
        // 金属档的底不是 surface 也不是 bg，而是**着色器算出来的金属面**：
        // 暗色下实测落在 #1A1E26 附近（球冠中心更亮、边缘更暗）。取 surface 会算偏松，
        // 未选中的 tab 文字在真机上直接读不出来（截图确认过）。
        val under =
            when {
                metal != null -> if (isDark) Color(0xFF22262F) else Color(0xFFC3CAD5)
                else -> colors.surface
            }
        // muted 求解：金属面上的未选中文字必须提到 4.5:1 以上。
        // 混到 text 只是起点——暗金属面吸光，实测还要再亮一档才「一眼看得见」，
        // 所以金属档额外朝 text 多混 12%（超过 AA 门槛后，可读性优先于"低调"）。
        val mutedBase = Contrast.mixUntil(colors.textMuted, colors.text, under, MIN_TEXT_CONTRAST)
        val muted =
            if (metal !=
                null
            ) {
                Contrast.mixUntil(mutedBase, colors.text, under, MIN_TEXT_CONTRAST * 1.15f)
            } else {
                mutedBase
            }
        val chipAlpha =
            metal?.let { METAL_CHIP_ALPHA } ?: CHIP_ALPHA
        // 金属档：选中胶囊是一枚**亮面水银珠**（着色器整体亮度高，实测浅色下接近 #D8DDE6）。
        // 浅色文字压上去等于消失——这条在真机上抓到过（选中 tab 文字被亮球吃掉）。
        // 所以文字改为深色：与亮金属形成 8:1 以上的对比，同时读作"刻在金属上的字"。
        val selected =
            when {
                // 深墨而非纯黑：纯黑在金属面上会有轻微的"贴上去"感；#05070B 足够黑又保留一点金属亲和
                metal != null -> Color(0xFF05070B)
                else -> colors.text
            }
        val chip =
            when {
                // 金属档的"珠色"不取 accent（那是页面的语义色），取一个近钢的冷灰做 F0 底，
                // 靠着色器的球冠明暗读出体积；accent 留给页面里的强调元素，不抢导航件。
                // 珠色取中明度钢灰：太亮会把深色文字吃掉，太暗则与浮岛本体的暗金属融成一片。
                // 真机取样：暗色 #6E7889、亮色 #97A3B6 时，深色文字对比 ≈ 7:1，珠子轮廓仍清晰。
                // 珠色亮度必须撑住深色文字：实测 4.17 → 需要再亮一档才过 4.5
                metal != null -> if (isDark) Color(0xFF9AA5B8) else Color(0xFFA8B4C6)
                else -> Contrast.adjustUntil(colors.accent, under, colors.onAccent, chipAlpha, MIN_TEXT_CONTRAST)
            }
        return IslandPalette(
            muted = muted,
            selected = selected,
            chip = chip,
            chipAlpha = chipAlpha,
            under = under,
            chipEdgeAlpha = if (metal != null) 0f else CHIP_EDGE_ALPHA,
        )
    }
}

/** 当前主题下的浮岛配色（主题 / 亮暗 / 玻璃档变化时重新求解） */
@Composable
fun rememberIslandPalette(): IslandPalette {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val isDark = LocalIsDark.current
    return remember(colors, metal, isDark) { IslandTokens.solve(colors, isDark, metal) }
}
