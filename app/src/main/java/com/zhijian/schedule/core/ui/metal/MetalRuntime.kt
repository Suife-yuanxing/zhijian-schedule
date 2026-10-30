package com.zhijian.schedule.core.ui.metal

import android.app.ActivityManager
import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.zhijian.schedule.core.theme.MetalSpec

/**
 * 水银镜面运行时：能力判定 + shader 工厂 + uniform 配置。
 *
 * 本文件是唯一引用 android.graphics.RuntimeShader（API 33）的位置；所有公开方法的调用点
 * 都必须先经 [supported] 短路，保证 minSdk 26 的类加载安全（与玻璃材质同一套纪律）。
 */
object MetalRuntime {
    /** 强降档预览开关：临时置 true 构建一次看哑光档，截图后还原 */
    var forceFallbackForPreview: Boolean = false

    private var supportedCache: Boolean? = null

    fun supported(context: Context): Boolean {
        supportedCache?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ok =
            !forceFallbackForPreview &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !am.isLowRamDevice
        if (DEBUG_LOG) {
            android.util.Log.i(
                "MetalRuntime",
                "supported=$ok forceFallback=$forceFallbackForPreview sdk=${Build.VERSION.SDK_INT} lowRam=${am.isLowRamDevice}",
            )
        }
        supportedCache = ok
        return ok
    }

    /** 诊断开关：置 true 构建一次即可在 logcat 里看到能力判定与着色器编译结果 */
    const val DEBUG_LOG = false

    /** 首次用某段源码创建 shader 时校验一次，编译失败立刻暴露（否则只会静默回退） */
    fun verify(
        tag: String,
        source: String,
    ): RuntimeShader =
        try {
            RuntimeShader(source).also {
                if (DEBUG_LOG) android.util.Log.i("MetalRuntime", "$tag shader compiled OK")
            }
        } catch (t: Throwable) {
            android.util.Log.e("MetalRuntime", "$tag shader FAILED: ${t.message}", t)
            throw t
        }

    /** 元件表面 shader（Brush 路径）：静态参数在创建时写入一次 */
    fun surfaceShader(spec: MetalSpec): RuntimeShader =
        verify("surface", MetalShaders.buildSurfaceSource()).also {
            it.setFloatUniform("uSpec", spec.specular)
            it.setFloatUniform("uBrush", spec.brushed)
            it.setFloatUniform("uIrid", spec.iridescence)
            it.setFloatUniform("uFilm", spec.filmNm)
            it.setFloatUniform("uEnviron", spec.environ)
            it.setFloatUniform("uGrain", spec.grain)
            it.setFloatUniform("uSpin", spec.brushAngle)
            it.setFloatUniform("uBlade", spec.blade)
            it.setFloatUniform("uBloom", spec.bloom)
            it.setFloatUniform("uContact", spec.contact)
        }

    /** 逐帧/逐状态更新的动态 uniform */
    fun updateSurface(
        shader: RuntimeShader,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        time: Float,
        touch: Offset,
        press: Float,
        tint: androidx.compose.ui.graphics.Color,
        face: androidx.compose.ui.graphics.Color,
        chrome: Float,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uTouch", touch.x, touch.y)
        shader.setFloatUniform("uPress", press)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
        shader.setFloatUniform("uFace", face.red, face.green, face.blue)
        shader.setFloatUniform("uChrome", chrome)
    }

    /** 屏幕空间反射 shader（Effect 路径）：随图层一起重建 */
    fun ssrShader(spec: MetalSpec): RuntimeShader =
        verify("ssr", MetalShaders.buildSsrSource()).also {
            it.setFloatUniform("uSpec", spec.specular)
            it.setFloatUniform("uBrush", spec.brushed)
            it.setFloatUniform("uIrid", spec.iridescence)
            it.setFloatUniform("uFilm", spec.filmNm)
            it.setFloatUniform("uEnviron", spec.environ)
            it.setFloatUniform("uMirror", spec.mirror)
            it.setFloatUniform("uReflGain", spec.reflectGain)
            it.setFloatUniform("uSpin", spec.brushAngle)
            it.setFloatUniform("uBlade", spec.blade)
            it.setFloatUniform("uBloom", spec.bloom)
            it.setFloatUniform("uContact", spec.contact)
        }

    /**
     * 把反射着色器挂到背景图层：uContent 按名绑定图层内容。
     * RenderEffect 对 uniform 取快照 → 静态参数必须在挂载前写齐，挂载后写入不生效。
     */
    fun attachSsr(
        layer: GraphicsLayer,
        shader: RuntimeShader,
    ) {
        layer.renderEffect =
            android.graphics.RenderEffect
                .createRuntimeShaderEffect(shader, "uContent")
                .asComposeRenderEffect()
    }

    fun updateSsr(
        shader: RuntimeShader,
        originX: Float,
        originY: Float,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
        touch: Offset,
        press: Float,
        tint: androidx.compose.ui.graphics.Color,
        face: androidx.compose.ui.graphics.Color,
        chrome: Float,
    ) {
        shader.setFloatUniform("uOrigin", originX, originY)
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uTouch", touch.x, touch.y)
        shader.setFloatUniform("uPress", press)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
        shader.setFloatUniform("uFace", face.red, face.green, face.blue)
        shader.setFloatUniform("uChrome", chrome)
    }

    /** 全屏环境 shader */
    fun ambientShader(spec: MetalSpec): RuntimeShader = verify("ambient", MetalShaders.buildAmbientSource())

    fun updateAmbient(
        shader: RuntimeShader,
        widthPx: Float,
        heightPx: Float,
        time: Float,
        gyro: Offset,
        floor: androidx.compose.ui.graphics.Color,
        cool: androidx.compose.ui.graphics.Color,
        warm: androidx.compose.ui.graphics.Color,
        bright: Float,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uGyro", gyro.x, gyro.y)
        shader.setFloatUniform("uFloor", floor.red, floor.green, floor.blue)
        shader.setFloatUniform("uCool", cool.red, cool.green, cool.blue)
        shader.setFloatUniform("uWarm", warm.red, warm.green, warm.blue)
        shader.setFloatUniform("uBright", bright)
    }
}
