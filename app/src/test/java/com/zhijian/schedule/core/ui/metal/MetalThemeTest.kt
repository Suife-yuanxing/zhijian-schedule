package com.zhijian.schedule.core.ui.metal

import com.zhijian.schedule.core.theme.ThemeIds
import com.zhijian.schedule.core.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水银镜面主题的契约测试：注册、参数合法性、两档物理模型互斥。
 *
 * 这些断言挡的是「改参数改到着色器算不出来」这类静默故障：
 * AGSL 编译错误只在运行时抛，参数越界则完全不报错、只是画面变脏。
 */
class MetalThemeTest {
    @Test
    fun `水银镜面已注册且取代液态玻璃`() {
        val ids = ThemePresets.all.map { it.id }
        assertTrue("chrome 必须在预设列表里", ids.contains(ThemeIds.CHROME))
        assertTrue("glass 已被移除", !ids.contains("glass"))
        assertEquals("预设数量：纸本/动态/素墨/纯黑/文具盒/水银 = 6", 6, ids.size)
    }

    @Test
    fun `只有 chrome 带金属材质，其余主题为 null`() {
        ThemePresets.all.forEach { preset ->
            if (preset.id == ThemeIds.CHROME) {
                assertNotNull("chrome 必须有 MetalSpec", preset.metal)
            } else {
                assertNull("${preset.id} 不应启用金属材质", preset.metal)
            }
        }
    }

    @Test
    fun `金属参数落在着色器可计算范围内`() {
        val spec = ThemePresets.chrome.metal!!
        assertTrue("chrome 0..1", spec.chrome in 0f..1f)
        assertTrue("brushed 0..1", spec.brushed in 0f..1f)
        assertTrue("iridescence 0..1", spec.iridescence in 0f..1f)
        assertTrue("mirror 0..1", spec.mirror in 0f..1f)
        assertTrue("grain 不能为负", spec.grain >= 0f)
        assertTrue("specular 必须为正，否则整块金属无高光", spec.specular > 0f)
        assertTrue("filmNm 必须为正，否则薄膜干涉退化成常数", spec.filmNm > 0f)
        assertTrue("environ 必须为正", spec.environ > 0f)
    }

    @Test
    fun `高光强度足够在暗室内被看见`() {
        val spec = ThemePresets.chrome.metal!!
        // 暗室（ambientBright 0.52）+ 金属 F0 偏冷：specular 低于 0.6 时倒角圈会消失，
        // 元件读作「一块贴纸」。这条门槛来自真机截图对比。
        assertTrue("specular 至少 0.6", spec.specular >= 0.6f)
    }

    @Test
    fun `反射增益不低于 1，暗底上才看得见镜像`() {
        val spec = ThemePresets.chrome.metal!!
        assertTrue(spec.reflectGain >= 1f)
    }
}
