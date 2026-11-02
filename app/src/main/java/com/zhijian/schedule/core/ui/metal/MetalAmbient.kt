package com.zhijian.schedule.core.ui.metal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

private class AmbientBrush(
    private val shader: Shader,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/**
 * 全屏环境底（水银镜面主题专用）：极暗房间 + 两团随重力漂移的柔光 + 地平线暗带 + 微弱颗粒。
 *
 * 为什么材质主题必须有它：金属的观感 100% 来自「它反射到了什么」。纯色底上的金属
 * 只能反射到纯色，看起来就是一块灰塑料。这层环境给了反射方向一个真实的房间——
 * 页面与材质因此是配合关系，而不是「一张背景 + 一堆控件」。
 *
 * 降档（无 shader）：三层径向渐变近似同一构图。
 */
@Composable
fun MetalAmbientBackdrop(
    clock: State<Float>?,
    gyro: State<Offset>?,
    modifier: Modifier = Modifier,
) {
    val spec = LocalCoursePreset.current.metal ?: return
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    val supported = remember(context) { MetalRuntime.supported(context) }
    val floor = if (isDark) spec.floorDark else spec.floorLight
    val cool = if (isDark) spec.coolDark else spec.coolLight
    val warm = if (isDark) spec.warmDark else spec.warmLight

    if (supported && clock != null) {
        val shader = remember(spec) { MetalRuntime.ambientShader(spec) }
        val brush = remember(shader) { AmbientBrush(shader) }
        androidx.compose.foundation.layout.Box(
            modifier
                .fillMaxSize()
                .drawBehind {
                    MetalRuntime.updateAmbient(
                        shader = shader,
                        widthPx = size.width,
                        heightPx = size.height,
                        time = clock.value,
                        gyro = gyro?.value ?: Offset.Zero,
                        floor = floor,
                        cool = cool,
                        warm = warm,
                        bright = spec.ambientBright,
                    )
                    drawRect(brush = brush)
                },
        )
        return
    }

    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(floor)
                // 冷光斑：左上偏内，半径接近整宽，压出「远处天光」的漫射
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(cool.copy(alpha = COOL_GLOW_ALPHA), Color.Transparent),
                            center = Offset(size.width * COOL_GLOW_X, size.height * COOL_GLOW_Y),
                            radius = size.width * COOL_GLOW_RADIUS,
                        ),
                    radius = size.width * COOL_GLOW_RADIUS,
                    center = Offset(size.width * COOL_GLOW_X, size.height * COOL_GLOW_Y),
                )
                // 暖光斑：右下，略小且更淡，与冷光斑形成对角张力
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(warm.copy(alpha = WARM_GLOW_ALPHA), Color.Transparent),
                            center = Offset(size.width * WARM_GLOW_X, size.height * WARM_GLOW_Y),
                            radius = size.width * WARM_GLOW_RADIUS,
                        ),
                    radius = size.width * WARM_GLOW_RADIUS,
                    center = Offset(size.width * WARM_GLOW_X, size.height * WARM_GLOW_Y),
                )
            },
    )
}

// 环境光斑的几何与强度：数值取自金属主题的目视调校，单独命名以便整体重调时只改一处。
private const val COOL_GLOW_X = 0.26f
private const val COOL_GLOW_Y = 0.08f
private const val COOL_GLOW_RADIUS = 0.9f
private const val COOL_GLOW_ALPHA = 0.55f
private const val WARM_GLOW_X = 0.82f
private const val WARM_GLOW_Y = 0.30f
private const val WARM_GLOW_RADIUS = 0.75f
private const val WARM_GLOW_ALPHA = 0.42f
