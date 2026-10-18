package com.zhijian.schedule

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
}
