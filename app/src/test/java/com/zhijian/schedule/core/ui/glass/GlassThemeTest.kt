package com.zhijian.schedule.core.ui.glass

import com.zhijian.schedule.core.theme.ThemeIds
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.glass.GlassShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassThemeTest {
    @Test
    fun glassPresetRegistered() {
        val preset = ThemePresets.byId(ThemeIds.GLASS)
        assertEquals(ThemeIds.GLASS, preset.id)
        assertEquals("液态玻璃", preset.name)
        assertNotNull(preset.glass)
        assertEquals(6, ThemePresets.all.size)
    }

    @Test
    fun onlyGlassPresetCarriesGlassSpec() {
        for (p in ThemePresets.all) {
            if (p.id == ThemeIds.GLASS) assertNotNull(p.id, p.glass) else assertNull(p.id, p.glass)
        }
    }

    @Test
    fun glassSpecValuesInRange() {
        val spec = ThemePresets.byId(ThemeIds.GLASS).glass!!
        assertTrue(spec.bodyAlphaLight in 0f..0.6f)
        assertTrue(spec.bodyAlphaDark in 0f..0.6f)
        assertTrue(spec.chipAlphaLight in 0f..1f)
        assertTrue(spec.chipAlphaDark in 0f..1f)
        assertTrue(spec.specular in 0f..1f)
        assertTrue(spec.flow in 0f..1f)
        assertTrue(spec.lens in 0f..0.5f)
        assertTrue(spec.mirror in 0f..1f)
        assertTrue(spec.tiltSpec in 0f..1f)
    }

    @Test
    fun waterShaderSourceContainsRequiredUniforms() {
        val src = GlassShaders.buildWaterGlassSource()
        for (u in listOf(
            "uSize",
            "uRadius",
            "uBand",
            "uTime",
            "uTint",
            "uBodyAlpha",
            "uSpec",
            "uFlow",
            "uTilt",
            "uTiltSpec",
        )) {
            assertTrue(
                u,
                src.contains("uniform float2 $u") ||
                    src.contains("uniform float3 $u") ||
                    src.contains("uniform float $u"),
            )
        }
        assertTrue(src.contains("sdRoundRect"))
        assertTrue(src.contains("half4 main(float2 fragCoord)"))
        // 零模糊/零捕获：shader 不得引用任何 sampler
        assertTrue(!src.contains("uniform shader"))
    }

    @Test
    fun lensShaderSamplesBackdropLayer() {
        val src = GlassShaders.buildLensGlassSource()
        // 折射面：必须有内容采样入口（唯一允许的 sampler）+ 条内定位与透镜/镜像强度；
        // 水膜已移出 effect（uniform 挂载即快照），动画走画笔路径
        assertTrue(src.contains("uniform shader uContent"))
        for (u in listOf("uOrigin", "uSize", "uRadius", "uLens", "uMirror", "uMirrorLen")) {
            assertTrue(
                u,
                src.contains("uniform float2 $u") ||
                    src.contains("uniform float3 $u") ||
                    src.contains("uniform float $u"),
            )
        }
        assertTrue(src.contains("uContent.eval"))
        // 效果输入为线性光、输出不回编：折射基线必须显式编码回 sRGB 才与原内容同亮度
        assertTrue(src.contains("lin2srgb"))
        assertTrue(src.contains("half4 main(float2 fragCoord)"))
    }

    @Test
    fun dropletShaderSourceContainsRequiredUniforms() {
        val src = GlassShaders.buildDropletSource()
        for (u in listOf("uSize", "uRadius", "uTime", "uTint", "uAlpha", "uSpec")) {
            assertTrue(
                u,
                src.contains("uniform float2 $u") ||
                    src.contains("uniform float3 $u") ||
                    src.contains("uniform float $u"),
            )
        }
        assertTrue(src.contains("sdRoundRect"))
        assertTrue(src.contains("half4 main(float2 fragCoord)"))
        assertTrue(!src.contains("uniform shader"))
    }
}
