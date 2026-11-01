package com.zhijian.schedule.core.ui.island

import com.zhijian.schedule.core.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 失败态抖动的波形与危险色的对比度。
 *
 * 这两件事都只在真机上「看起来对不对」，本机单测守的是它们的**端点与下限**：
 * 波形起点／终点必须是 0，否则胶囊会在出现那一帧凭空一跳、或结束时硬切回原位；
 * 危险色在每一套预设的浮岛面上都必须够亮，否则「没成」这件事根本看不见。
 * 两种毛病都不会抛异常，只会安静地做错事。
 */
class IslandHintTest {
    @Test
    fun shakeStartsAndEndsAtZero() {
        assertEquals("p=0 必须没有位移，否则出现那一帧会凭空一跳", 0f, dampedShake(0f), 1e-6f)
        assertEquals("p=1 必须回到原位，否则抖动结尾是硬切", 0f, dampedShake(1f), 1e-6f)
    }

    @Test
    fun shakeIsBoundedByOne() {
        // 幅度是 6dp：比例一旦超过 1，胶囊会顶出既定的安全边距
        for (i in 0..1000) {
            val v = dampedShake(i / 1000f)
            assertTrue("比例 $v 越界，位移会超出既定的 6dp", abs(v) <= 1f + 1e-6f)
        }
    }

    @Test
    fun shakeActuallyMoves() {
        // 逐点采样取峰值：波形若被写成常量 0，失败态就只剩颜色变化，
        // 而那正是「用户没看屏幕时完全错过」的场景
        val peak = (0..1000).maxOf { abs(dampedShake(it / 1000f)) }
        assertTrue("峰值只有 $peak，抖动等于没做", peak > 0.5f)
    }

    @Test
    fun shakeDecaysTowardsTheEnd() {
        // 前半程峰值须明显大于后半程：失败的手感要「一下比一下弱」，
        // 恒定幅度会被读成「界面在持续抖」，像是卡住了
        fun peakOf(
            from: Int,
            to: Int,
        ) = (from..to).maxOf { abs(dampedShake(it / 1000f)) }
        val early = peakOf(0, 400)
        val late = peakOf(600, 1000)
        assertTrue("前半程 $early 应大于后半程 $late，否则读作持续抖动", early > late * 1.5f)
    }

    @Test
    fun shakeClampsOutOfRangeProgress() {
        // tween 收尾的浮点尾差偶尔会给出 1.0000001：夹住而不是外推
        assertEquals(0f, dampedShake(-1f), 1e-6f)
        assertEquals(0f, dampedShake(2f), 1e-6f)
    }

    /**
     * 危险色必须在浮岛面上达到图形门槛。
     *
     * 最容易出问题的组合是「墨夜」与「水银镜面」的暗档：浮岛面比正文底色更亮，
     * 页面里那个 #E5484D 压上去只剩约 3.1:1，叉号与进度条会糊进背景。
     * 失败提示最不能出的问题就是「看不清」。
     */
    @Test
    fun dangerColorMeetsGraphicContrastOnEveryPreset() {
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val solved = IslandTokens.dangerOn(colors, isDark, preset.metal)
                // 判据的底色与 dangerOn 内部一致：非金属档压 surface
                val ratio = Contrast.ratio(solved, colors.surface)
                val label = preset.id + if (isDark) "/dark" else "/light"
                assertTrue(
                    "$label 的危险色只有 $ratio:1，低于图形门槛 3.0",
                    ratio >= IslandTokens.MIN_ICON_CONTRAST,
                )
            }
        }
    }

    /**
     * 两档语气**必须**拿到不同的图标。
     *
     * 这条钉的是一件已经量过的事：「纸本暖白」的强调色是陶土橘 #D97757，
     * 与失败用的正红 #E5484D 之间只有 1.25:1——颜色这一层在暖色主题上几乎不传递差别。
     * 所以真正把成功与失败分开的是图标（对勾 / 叉号）与文案，不是颜色
     * （WCAG 2.1 1.4.1 Use of Color）。
     *
     * 这条断言看着像废话，但它防的是一次很自然的"优化"：有人觉得「都是图标，
     * 统一用对勾更简洁」，于是两档就只剩明暗之差——色觉不同的用户从此分不出成败。
     */
    @Test
    fun tonesUseDistinctIcons() {
        assertNotEquals(
            "成功与失败必须是两个不同的图标，否则颜色一撞就分不出来",
            defaultIcon(IslandHintTone.Done),
            defaultIcon(IslandHintTone.Failed),
        )
    }

    /**
     * 危险色必须与浮岛**底色**拉开图形门槛（而不是与强调色拉开）。
     *
     * 这是失败提示真正的可读性下限：叉号与进度条压在胶囊面上，
     * 与胶囊底色分不开就等于没画。与强调色的关系不设门槛——那件事交给图标解决。
     */
    @Test
    fun dangerIsReadableAgainstPillSurface() {
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val danger = IslandTokens.dangerOn(colors, isDark, preset.metal)
                val label = preset.id + if (isDark) "/dark" else "/light"
                val ratio = Contrast.ratio(danger, colors.surface)
                assertTrue(
                    label + " 的危险色压在胶囊面上只有 " + ratio + ":1，图形门槛是 3.0",
                    ratio >= IslandTokens.MIN_ICON_CONTRAST,
                )
            }
        }
    }
}
