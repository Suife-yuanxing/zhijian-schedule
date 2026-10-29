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
                assertPaletteBrightness(preset, sources, isDark)
            }
        }
    }

    /** 亮色卡面必须够浅（> 0.75）、暗色必须够深（< 0.20），避免课块与页面底色糊成一片 */
    private fun assertPaletteBrightness(
        preset: ThemePreset,
        sources: List<Color>,
        isDark: Boolean,
    ) {
        for (src in sources) {
            val lum = luminance(courseTone(src, isDark, preset.toneBias).container)
            if (isDark) {
                assertTrue("${preset.name} 暗色卡面不够深: $lum", lum < 0.20)
            } else {
                assertTrue("${preset.name} 亮色卡面不够浅: $lum", lum > 0.75)
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
        // 素墨砍到 55%，文具盒拉到 350%——两者对同一源色的彩度必须拉开
        val src = Color(0xFFC98A00)

        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        val sumiSpread = spread(courseTone(src, false, ThemePresets.minimal.toneBias).container)
        val kitSpread = spread(courseTone(src, false, ThemePresets.candy.toneBias).container)
        assertTrue("文具盒的彩度应高于素墨: $sumiSpread vs $kitSpread", kitSpread > sumiSpread)
    }

    @Test
    fun `文具盒亮色课块比素墨显著更艳且八色仍可分`() {
        // 这条钉住「A 方案」要解决的那个真机问题：旧参数下素墨与文具盒对同一门课
        // 只差 RGB [1,6,10]，肉眼等同；同时八色两两最小 ΔE 只有 0.0075，几乎糊成一片。
        //
        // 实测：把 lightL 从 0.950 让到 0.930、lightChroma 从 1.20 提到 3.50 后，
        // 平均彩度 ×1.93、八色最小两两 ΔE ×1.37。门槛取实测值略低一档留调色余量，
        // 但足以拦住「悄悄把系数调回去」的回归。
        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

        val kitAvg = kit.map { spread(courseTone(it, false, ThemePresets.candy.toneBias).container) }.average()
        val sumiAvg = sumi.map { spread(courseTone(it, false, ThemePresets.minimal.toneBias).container) }.average()
        assertTrue("文具盒平均彩度应达素墨 1.5 倍以上: $kitAvg vs $sumiAvg", kitAvg > sumiAvg * 1.5)

        fun oklab(c: Color): DoubleArray {
            fun lin(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
            }
            val r = lin(c.red)
            val g = lin(c.green)
            val b = lin(c.blue)
            val l = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b).pow(1.0 / 3.0)
            val m = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b).pow(1.0 / 3.0)
            val s = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b).pow(1.0 / 3.0)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }
        val labs = kit.map { oklab(courseTone(it, false, ThemePresets.candy.toneBias).container) }
        var minDistance = Double.MAX_VALUE
        for (i in labs.indices) {
            for (j in i + 1 until labs.size) {
                val a = labs[i]
                val b = labs[j]
                val d = ((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2)).pow(0.5)
                if (d < minDistance) minDistance = d
            }
        }
        assertTrue("文具盒八色最近的一对只差 $minDistance，肉眼分不开", minDistance > 0.009)
    }

    @Test
    fun `真中性源色走中性分支保持完全中性`() {
        // 这里必须用 OKLCH chroma < 0.012 的**真中性色**：它们走 courseTone 的 null 分支，
        // 容器彩度恒为 0，与 lightChroma 无关——这才是「中性不被染色」的真实语义。
        //
        // 旧版用 #8A8F98 测这条，但它在 OKLCH 里 hue 262.4°、chroma 0.0146，走的是彩色分支，
        // 断言能过纯粹是因为当时 lightChroma 只有 1.2（染色量被压得很小）。文具盒把系数提到
        // 3.5 之后它如实地泛蓝，旧断言于是暴露了它一直测错了对象。
        val neutrals = listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFC0C0C0, 0xFF7A7A7A)
        val presets = listOf(ThemePresets.minimal, ThemePresets.amoled, ThemePresets.candy)
        for (preset in presets) {
            for (isDark in listOf(false, true)) {
                for (src in neutrals) {
                    val c = courseTone(Color(src), isDark, preset.toneBias).container
                    val s = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                    assertEquals("${preset.name} 中性源色被上了色 $src (isDark=$isDark)", 0f, s, 0.001f)
                }
            }
        }
    }

    @Test
    fun `近中性冷灰只被轻微染色而不是变成一块蓝`() {
        // #8A8F98 走彩色分支（chroma 0.0146），系数放大后必然泛蓝。允许染色，但必须守住
        // 上界：它出现在色板第 8 位，用户看到的应该仍是"偏冷的灰"，不是一种蓝。
        val coolGray = Color(0xFF8A8F98)
        val presets = listOf(ThemePresets.minimal, ThemePresets.amoled, ThemePresets.candy)
        for (preset in presets) {
            for (isDark in listOf(false, true)) {
                val c = courseTone(coolGray, isDark, preset.toneBias).container
                val s = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                assertTrue("${preset.name} 冷灰被染过头 (isDark=$isDark): $s", s <= 0.075f)
            }
        }
    }
}
