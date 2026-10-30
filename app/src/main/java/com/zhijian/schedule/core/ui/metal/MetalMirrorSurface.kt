package com.zhijian.schedule.core.ui.metal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/**
 * 镜面反射浮岛（水银镜面主题 shader 档专用）。
 *
 * 与玻璃浮岛的 glassBackdropSurface 是同一挂载技术的镜像实现：
 * 玻璃沿「边缘法线向心采样」把背景弯折（折射）；金属沿「反射方向」采样并把强度压到菲涅尔权重上
 * （正对处映得多、掠射处映得少且偏灯），因此同一份内容图层呈现出完全相反的物理行为。
 *
 * 降档（无 shader）：退回 metalSurface 的高抛光 Brush 档，视觉结构一致，只是不映真实内容。
 */
@Composable
fun Modifier.metalMirrorSurface(
    shape: RoundedCornerShape,
    touch: MetalTouchState?,
    clock: State<Float>?,
    backdrop: MetalBackdropState,
    originInLayer: () -> Offset,
): Modifier {
    val spec = LocalCoursePreset.current.metal ?: return this
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    val supported = remember(context) { MetalRuntime.supported(context) }
    if (!supported || clock == null) {
        return this.metalSurface(
            shape = shape,
            touch = touch,
            clock = clock,
            chromeOverride = 0.58f,
        )
    }
    // shader 与图层同生命周期：RuntimeShader 的图层采样绑定会固化在首个 effect 上
    val ssrShader = remember(backdrop.layer, spec) { MetalRuntime.ssrShader(spec) }
    val tint = if (isDark) spec.tintDark else spec.tintLight
    val face = if (isDark) spec.faceDark else spec.faceLight

    return this.drawBehind {
        val r = shape.radiusPx(size, this)
        val origin = originInLayer()
        // 惰性挂载：本绘制在内容录制之后执行，挂载时图层已有内容；
        // 静态 uniform（原点/尺寸/半径）必须在挂载前写齐——effect 会对 uniform 取快照
        val signature = SsrSignature(origin.x, origin.y, size.width, size.height, r)
        if (signature != backdrop.ssrAttachSignature) {
            MetalRuntime.updateSsr(
                shader = ssrShader,
                originX = origin.x,
                originY = origin.y,
                widthPx = size.width,
                heightPx = size.height,
                radiusPx = r,
                touch = touch?.touch ?: Offset(0.5f, 0.5f),
                press = touch?.pressValue ?: 0f,
                tint = tint,
                face = face,
                chrome = 0.58f,
            )
            MetalRuntime.attachSsr(backdrop.layer, ssrShader)
            backdrop.ssrAttachSignature = signature
        }
        val pill =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r), CornerRadius(r, r)))
            }
        clipPath(pill) {
            translate(left = -origin.x, top = -origin.y) { drawLayer(backdrop.layer) }
        }
    }
}

/** 反射 effect 的挂载签名：这些静态参数变化时需要重新挂载 */
private data class SsrSignature(
    val originX: Float,
    val originY: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
)
