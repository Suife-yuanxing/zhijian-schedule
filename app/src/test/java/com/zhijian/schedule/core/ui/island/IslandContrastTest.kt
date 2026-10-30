package com.zhijian.schedule.core.ui.island

import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.island.Contrast
import com.zhijian.schedule.core.ui.island.IslandTokens
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮岛对比度护栏。
 *
 * 这些用例钉住的是一次真机复现的问题：浮岛直接借用页面 textMuted，
 * 换成 surface 当底之后「纸本暖白」掉到 3.80、「清爽极简」掉到 2.99，
 * 选中液滴上的白字最差只有 2.75 —— 户外基本读不出来。
 *
 * 从此以后任何新主题只要忘了给浮岛做对比度求解，这里就会红。
 */
class IslandContrastTest {
    private val allPresets =
        listOf(
            ThemePresets.paper,
            ThemePresets.dynamic,
            ThemePresets.minimal,
            ThemePresets.amoled,
            ThemePresets.candy,
            ThemePresets.chrome,
        )

    @Test
    fun `未选中标签在每套主题每一种明暗下都达到 AA`() {
        for (preset in allPresets) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val palette = IslandTokens.solve(colors, isDark, preset.metal)
                val ratio = Contrast.ratio(palette.muted, palette.under)
                assertTrue(
                    "${preset.name} 未选中标签对比度 $ratio 低于 4.5",
                    ratio >= IslandTokens.MIN_TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun `选中态文字压在胶囊上达到 AA`() {
        for (preset in allPresets) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val palette = IslandTokens.solve(colors, isDark, preset.metal)
                val composed = Contrast.composite(palette.chip, palette.under, palette.chipAlpha)
                val ratio = Contrast.ratio(palette.selected, composed)
                assertTrue(
                    "${preset.name} 选中态对比度 $ratio 低于 4.5",
                    ratio >= IslandTokens.MIN_TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun `收敛态图标仍达到图形门槛`() {
        // 收起后标签消失，只剩图标；图形门槛 3.0 是兜住「收起来也别看不见」的下限
        for (preset in allPresets) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val palette = IslandTokens.solve(colors, isDark, preset.metal)
                val ratio = Contrast.ratio(palette.muted, palette.under)
                assertTrue(
                    "${preset.name} 收敛态图标对比度 $ratio 低于 3.0",
                    ratio >= IslandTokens.MIN_ICON_CONTRAST,
                )
            }
        }
    }

    @Test
    fun `求解器不会把已经达标的主色越调越远`() {
        val under = Color.White
        val base = Color(0xFF6750A4)
        val adjusted = Contrast.adjustUntil(base, under, Color.White, 0.90f, 4.5)
        // 动态取色原本就 5.10 达标，求解器应当原样返回
        assertTrue(adjusted == base)
    }

    @Test
    fun `非金属主题必须描边兜住胶囊边界`() {
        // 纸本暖白的淡胶囊与浮岛底只有 1.14:1，边界会消失；描边是位移可读的唯一保证。
        // 金属档的珠子本身有球冠明暗与暗边，再描边会读成「框」——所以那里应当是 0。
        for (preset in allPresets) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val palette = IslandTokens.solve(colors, isDark, preset.metal)
                if (preset.metal == null) {
                    assertTrue("${preset.name} 非金属主题缺胶囊描边", palette.chipEdgeAlpha > 0f)
                } else {
                    assertTrue("${preset.name} 金属主题不该描边", palette.chipEdgeAlpha == 0f)
                }
            }
        }
    }

    @Test
    fun `胶囊填充浓度保持克制不喧宾夺主`() {
        // M3 官方指示器与容器也只有 1.13:1；这是背景不是主体。
        // 若有人把 CHIP_ALPHA 调到 0.3 以上，这里会红——那是把导航件变成色块了。
        assertTrue(IslandTokens.CHIP_ALPHA <= 0.2f)
    }

    @Test
    fun `对比度公式与 WCAG 参考值一致`() {
        // 黑白 21:1、同色 1:1，是 WCAG 定义的两端
        assertTrue(Contrast.ratio(Color.Black, Color.White) > 20.9)
        assertTrue(Contrast.ratio(Color.White, Color.White) == 1.0)
    }
}
