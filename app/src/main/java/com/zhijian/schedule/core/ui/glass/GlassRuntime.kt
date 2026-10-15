package com.zhijian.schedule.core.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.zhijian.schedule.core.theme.GlassSpec

/**
 * 清水玻璃运行时：能力判定 + shader 工厂与 uniform 配置。
 * 本文件是唯一引用 android.graphics.RuntimeShader（API 33）的位置；
 * 所有公开方法的调用点都必须先经 [supported] 短路，保证 minSdk 26 类加载安全。
 */
object GlassRuntime {

    /** 真机降档预览开关（临时置 true 构建一次，截图后还原） */
    var forceFallbackForPreview: Boolean = false

    private var supportedCache: Boolean? = null

    fun supported(context: Context): Boolean {
        supportedCache?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ok = !forceFallbackForPreview &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !am.isLowRamDevice
        supportedCache = ok
        return ok
    }

    fun waterGlassShader(spec: GlassSpec): RuntimeShader =
        RuntimeShader(GlassShaders.buildWaterGlassSource()).also {
            it.setFloatUniform("uSpec", spec.specular)
            it.setFloatUniform("uFlow", spec.flow)
            it.setFloatUniform("uTiltSpec", spec.tiltSpec)
        }

    fun updateWater(
        shader: RuntimeShader,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        bandPx: Float,
        time: Float,
        tint: Color,
        bodyAlpha: Float,
        tilt: Offset,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uBand", bandPx)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
        shader.setFloatUniform("uBodyAlpha", bodyAlpha)
        shader.setFloatUniform("uTilt", tilt.x, tilt.y)
    }

    fun lensShader(spec: GlassSpec): RuntimeShader =
        RuntimeShader(GlassShaders.buildLensGlassSource()).also {
            it.setFloatUniform("uMirror", spec.mirror)
        }

    /**
     * 把折射着色器挂到背景图层上：uContent 自动采样图层内容（创建 Effect 时按名绑定）。
     * 注意：Effect 对 uniform 取快照——必须在挂载「之前」把静态参数写齐，挂载后再写不生效。
     */
    fun attachLensEffect(layer: GraphicsLayer, shader: RuntimeShader) {
        layer.renderEffect = android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "uContent")
            .asComposeRenderEffect()
    }

    fun updateLens(
        shader: RuntimeShader,
        originX: Float,
        originY: Float,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        lensFactor: Float,
    ) {
        shader.setFloatUniform("uOrigin", originX, originY)
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uLens", lensFactor * heightPx)
        shader.setFloatUniform("uMirrorLen", lensFactor * heightPx * 1.0f)
    }

    fun dropletShader(spec: GlassSpec): RuntimeShader =
        RuntimeShader(GlassShaders.buildDropletSource()).also {
            it.setFloatUniform("uSpec", spec.specular)
        }

    fun updateDroplet(
        shader: RuntimeShader,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        time: Float,
        tint: Color,
        alpha: Float,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
        shader.setFloatUniform("uAlpha", alpha)
    }
}
