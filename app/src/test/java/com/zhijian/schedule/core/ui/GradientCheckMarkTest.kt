package com.zhijian.schedule.core.ui

import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.island.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渐变勾选标记与主题派生渐变的护栏。
 *
 * 钉住的是两次真机事故：
 * 1. 未选中态原本只有一层「白色 24%」的半透明底，压在纸色页面上与底色几乎同色，
 *    真机截图里整枚标记等于不存在 —— 所以 [GradientCheckMark] 必须支持 idleRingColor，
 *    且调用处必须传一个看得见的颜色。
 * 2. 勾原本一律用白色。浅色主题的强调色提亮之后接近白，白勾压上去实测约 2.2:1，
 *    远看是一枚空圆 —— 所以 [gradientStrokeColor] 要按亮度反解，而不是写死白色。
 */
class GradientCheckMarkTest {
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
    fun `勾的颜色在每套主题下都与渐变底色达到图形门槛`() {
        for (preset in allPresets) {
            for (isDark in listOf(false, true)) {
                val accent = (if (isDark) preset.dark else preset.light).accent
                val gradient = accentGradient(accent)
                val stroke = gradientStrokeColor(gradient)
                // 勾横跨整条渐变，最坏的落点在亮度最接近勾色的那一端
                val worst =
                    gradient.minOf { stop ->
                        Contrast.ratio(stroke, stop)
                    }
                assertTrue(
                    "${preset.name}(isDark=$isDark) 勾色 $stroke 与渐变 $gradient 最差对比 $worst 低于 3.0",
                    worst >= 3.0,
                )
            }
        }
    }

    @Test
    fun `派生渐变保留强调色的色相方向`() {
        // 起点压深、终点提亮——中间那一停必须是强调色本身，否则勾选态与主题脱钩
        for (preset in allPresets) {
            val accent = preset.light.accent
            val gradient = accentGradient(accent)
            assertEquals(3, gradient.size)
            assertEquals(accent, gradient[1])
        }
    }

    @Test
    fun `深色强调色仍然用白勾`() {
        // 墨夜档的强调色是深紫，提亮后依然压得住白勾 —— 不能因为浅色档的毛病改成一律深勾
        val darkAccent = ThemePresets.amoled.light.accent
        assertEquals(Color.White, gradientStrokeColor(accentGradient(darkAccent)))
    }

    @Test
    fun `浅色强调色改用深勾`() {
        // 成功绿提亮后接近白，白勾不可读
        val lightAccent = Color(0xFF2BB673)
        val stroke = gradientStrokeColor(accentGradient(lightAccent))
        assertTrue("浅色渐变应当换深勾，实际 $stroke", stroke != Color.White)
    }

    @Test
    fun `空渐变不会崩`() {
        assertEquals(Color.White, gradientStrokeColor(emptyList()))
    }
}
