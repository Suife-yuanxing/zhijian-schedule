package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 主题级「课块面色」护栏。
 *
 * 钉住两件事：
 *
 * 1. **主题必须真的能改变课块**。改版前 `courseTone` 只吃 (source, isDark)，同一门课在
 *    六套主题里渲染出一模一样的卡面——主题切换只换得了页面底色，换不了课块性格。
 *    [CourseToneBias] 接入后，三套重构主题的卡面必须两两不同。
 *
 * 2. **差异化不能破坏原有的可读性保证**。[CourseToneTest] 已经钉住 ink/container ≥ 7、
 *    inkMuted/container ≥ 4.5、亮色卡面亮度 > 0.75、暗色 < 0.20、八色不撞车；
 *    这里用三套新色板再跑一遍同样的门槛，防止「为了个性把对比度调崩」。
 */
class CourseToneBiasTest {
    private val sumi =
        listOf(
            0xFF3A5A7A,
            0xFF3F6B57,
            0xFF8A5A3B,
            0xFF6B4E6E,
            0xFF2F5F6B,
            0xFF6E6A3A,
            0xFF56667A,
            0xFF5A5F66,
        ).map { Color(it) }

    private val lamp =
        listOf(
            0xFFE0A243,
            0xFFC97B52,
            0xFF94A85B,
            0xFF4FA98A,
            0xFF4E9DB3,
            0xFFA98BC4,
            0xFFC9718A,
            0xFF8C9199,
        ).map { Color(it) }

    private val kit =
        listOf(
            0xFFC98A00,
            0xFFF26A2E,
            0xFFE8465C,
            0xFFE85C90,
            0xFF7C5CFF,
            0xFF2F8FEF,
            0xFF22B37D,
            0xFF14A8A2,
        ).map { Color(it) }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun rgbKey(c: Color): Triple<Int, Int, Int> =
        Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private val rebuilt =
        listOf(
            ThemePresets.minimal to sumi,
            ThemePresets.amoled to lamp,
            ThemePresets.candy to kit,
        )

    @Test
    fun `默认偏置与改版前的硬编码行为完全一致`() {
        // 不传 bias 的旧调用点不能因为这次改动换色，否则纸本/动态/液态三套会被悄悄改版
        val legacy = listOf(0xFFC4704F, 0xFF4E9B8B, 0xFF5B8DB8, 0xFF8A8F98).map { Color(it) }
        for (src in legacy) {
            for (isDark in listOf(false, true)) {
                val implicit = courseTone(src, isDark)
                val explicit = courseTone(src, isDark, CourseToneBias.Default)
                assertEquals("默认偏置不该改变派生结果 (isDark=$isDark)", implicit, explicit)
            }
        }
    }

    @Test
    fun `新色板在各自主题下保持 AAA 正文与 AA 次要文字`() {
        for ((preset, sources) in rebuilt) {
            for (isDark in listOf(false, true)) {
                for (src in sources) {
                    val tone = courseTone(src, isDark, preset.toneBias)
                    assertTrue(
                        "${preset.name} ink/container 不足 (isDark=$isDark) $src",
                        contrast(tone.ink, tone.container) >= 7.0,
                    )
                    assertTrue(
                        "${preset.name} inkMuted/container 不足 (isDark=$isDark) $src",
                        contrast(tone.inkMuted, tone.container) >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun `新色板仍是八种可区分的卡面`() {
        for ((preset, sources) in rebuilt) {
            for (isDark in listOf(false, true)) {
                val keys = sources.map { rgbKey(courseTone(it, isDark, preset.toneBias).container) }
                assertEquals("${preset.name} 撞色 (isDark=$isDark): $keys", 8, keys.toSet().size)
            }
        }
    }

    @Test
    fun `新色板仍落在纸面与深底的安全亮度区间`() {
        for ((preset, sources) in rebuilt) {
            for (isDark in listOf(false, true)) {
                for (src in sources) {
                    val lum = luminance(courseTone(src, isDark, preset.toneBias).container)
                    if (isDark) {
                        assertTrue("${preset.name} 暗色卡面不够深: $lum", lum < 0.20)
                    } else {
                        assertTrue("${preset.name} 亮色卡面不够浅: $lum", lum > 0.75)
                    }
                }
            }
        }
    }

    @Test
    fun `同一门课在三套主题下渲染出三种卡面`() {
        // 这条是本次重构的核心断言：主题必须能改变课块，而不是只换页面底色
        val shared = Color(0xFF3A7DFF) // 蓝：三套主题都能取到
        val pairs = rebuilt.map { (preset, _) -> rgbKey(courseTone(shared, false, preset.toneBias).container) }
        assertEquals("三套主题的亮色卡面重复了：$pairs", 3, pairs.toSet().size)

        val darkPairs = rebuilt.map { (preset, _) -> rgbKey(courseTone(shared, true, preset.toneBias).container) }
        assertEquals("三套主题的暗色卡面重复了：$darkPairs", 3, darkPairs.toSet().size)
    }

    @Test
    fun `彩度偏置确实按比例生效`() {
        // 素墨砍到 55%，文具盒拉到 120%——两者对同一源色的彩度必须拉开
        val src = Color(0xFFC98A00)

        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        val sumiSpread = spread(courseTone(src, false, ThemePresets.minimal.toneBias).container)
        val kitSpread = spread(courseTone(src, false, ThemePresets.candy.toneBias).container)
        assertTrue("文具盒的彩度应高于素墨: $sumiSpread vs $kitSpread", kitSpread > sumiSpread)
    }

    @Test
    fun `中性课程色在偏置下仍保持中性`() {
        val gray = Color(0xFF8A8F98)
        val presets = listOf(ThemePresets.minimal, ThemePresets.amoled, ThemePresets.candy)
        for (preset in presets) {
            for (isDark in listOf(false, true)) {
                val c = courseTone(gray, isDark, preset.toneBias).container
                val s = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                assertTrue("${preset.name} 灰课被上了色 (isDark=$isDark): $s", s < 0.04f)
            }
        }
    }
}
