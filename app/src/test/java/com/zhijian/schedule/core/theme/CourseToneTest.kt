package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.courseTone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程色 → 同色系色调的硬约束。
 *
 * 旧实现（原色 × 14% alpha）的两个毛病在这里被钉住：
 * 一是对比度不可控（正文只有 3.53:1），二是八种色相被透明度洗成几乎同一个浅橙。
 */
class CourseToneTest {

    private val sources = listOf(
        0xFFE5484D, // 红
        0xFFF2933C, // 橙
        0xFFD9A400, // 琥珀
        0xFF4CAF50, // 绿
        0xFF1E9E8F, // 青
        0xFF3B82F6, // 蓝
        0xFF9B7DB8, // 紫（旧实现里色相会算回 0°）
        0xFF8A8F98, // 中性灰（走中性分支）
    ).map { Color(it) }

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `light tones keep body text at AAA and secondary at AA`() {
        sources.forEach { src ->
            val tone = courseTone(src, isDark = false)
            assertTrue("ink/container of $src", contrast(tone.ink, tone.container) >= 7.0)
            assertTrue("inkMuted/container of $src", contrast(tone.inkMuted, tone.container) >= 4.5)
        }
    }

    @Test
    fun `dark tones keep the same guarantees`() {
        sources.forEach { src ->
            val tone = courseTone(src, isDark = true)
            assertTrue("ink/container of $src", contrast(tone.ink, tone.container) >= 7.0)
            assertTrue("inkMuted/container of $src", contrast(tone.inkMuted, tone.container) >= 4.5)
        }
    }

    @Test
    fun `light containers are tinted paper and dark containers are deep`() {
        sources.forEach { src ->
            assertTrue(luminance(courseTone(src, false).container) > 0.75)
            assertTrue(luminance(courseTone(src, true).container) < 0.20)
        }
    }

    @Test
    fun `eight sources stay eight distinguishable containers`() {
        listOf(false, true).forEach { dark ->
            val rounded = sources.map { src ->
                val c = courseTone(src, dark).container
                Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
            }
            assertEquals("dark=$dark 撞色: $rounded", 8, rounded.toSet().size)
        }
    }

    @Test
    fun `neutral source produces a neutral container instead of a surprise hue`() {
        val tone = courseTone(Color(0xFF8A8F98), isDark = false)
        val spread = maxOf(tone.container.red, tone.container.green, tone.container.blue) -
            minOf(tone.container.red, tone.container.green, tone.container.blue)
        assertTrue("灰底被上了色: $spread", spread < 0.04f)
    }

    @Test
    fun `border line sits between container and ink`() {
        sources.forEach { src ->
            val tone = courseTone(src, isDark = false)
            val lc = luminance(tone.container)
            val ll = luminance(tone.line)
            val li = luminance(tone.ink)
            assertTrue("line 应比底色深: $src", ll < lc)
            assertTrue("line 应比正文浅: $src", ll > li)
        }
    }

    // ---- 交互用的高光 / 暗面：同色相，而不是纯白 + 纯黑 ----

    private fun spread(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    /** 主通道：色相在 sRGB 里最直接的表现就是这个索引没变 */
    private fun dominant(c: Color): Int {
        val v = floatArrayOf(c.red, c.green, c.blue)
        return v.indices.maxByOrNull { v[it] } ?: 0
    }

    /** 中性源（灰）走中性分支、色相本就无意义，这组约束只对有色课程成立 */
    private val chromatic = sources.filter { spread(it) > 0.1f }

    @Test
    fun `highlight is brighter than the card and shade is darker`() {
        listOf(false, true).forEach { dark ->
            chromatic.forEach { src ->
                val tone = courseTone(src, dark)
                val lc = luminance(tone.container)
                assertTrue("highlight 应亮于卡面 (dark=$dark): $src", luminance(tone.highlight) > lc)
                assertTrue("shade 应暗于卡面 (dark=$dark): $src", luminance(tone.shade) < lc)
            }
        }
    }

    @Test
    fun `highlight and shade keep the course hue instead of going pure white or black`() {
        listOf(false, true).forEach { dark ->
            chromatic.forEach { src ->
                val tone = courseTone(src, dark)
                // 纯白/纯黑的通道差为 0 —— 一旦被"洗白"或"压黑"，这条就会挂
                assertTrue("highlight 褪成纯白 (dark=$dark): $src", spread(tone.highlight) > 0.004f)
                assertTrue("shade 抽成纯黑 (dark=$dark): $src", spread(tone.shade) > 0.01f)
                // 色相守恒：最亮的那个通道必须还是同一个
                assertEquals(
                    "highlight 换色相 (dark=$dark): $src",
                    dominant(tone.container),
                    dominant(tone.highlight),
                )
                assertEquals(
                    "shade 换色相 (dark=$dark): $src",
                    dominant(tone.container),
                    dominant(tone.shade),
                )
            }
        }
    }
}
