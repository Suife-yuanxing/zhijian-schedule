package com.zhijian.schedule.core.ui.metal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.zhijian.schedule.core.theme.LocalIsDark

/**
 * 反射背景：根内容的**绘制指令**被录进 [layer]（不是像素拷贝，零截屏），
 * 浮岛用 AGSL 沿反射方向采样该图层，于是金属真的映出下方滚动的页面。
 *
 * 与玻璃的 GlassBackdrop 是同一技术路线、相反光学：玻璃把背景「折射弯」，
 * 金属把背景「按镜面方向反射、并按菲涅尔衰减」。
 */
@Stable
class MetalBackdropState internal constructor(
    val layer: GraphicsLayer,
    private val contentOrigin: MutableState<Offset>,
) {
    /** 内容层左上角在窗口坐标中的位置（px） */
    var contentOriginInWindow: Offset
        get() = contentOrigin.value
        internal set(value) {
            contentOrigin.value = value
        }

    /** 反射 effect 的挂载签名（effect 挂载时对 uniform 取快照，参数变了要重挂） */
    internal var ssrAttachSignature: Any? = null

    internal fun onContentPositioned(origin: Offset) {
        contentOriginInWindow = origin
    }
}

/**
 * 金属主题 + shader 档才启用；其余组合返回 null，调用方零成本。
 * 配置变更（深浅色/旋转）时重建图层：RenderEffect 的图层光栅缓存不会自动失效，
 * 切换瞬间缓存到过渡空帧会让反射永久采到黑。
 */
@Composable
fun rememberMetalBackdrop(enabled: Boolean): MetalBackdropState? {
    if (!enabled) return null
    val context = LocalContext.current
    val supported = remember(context) { MetalRuntime.supported(context) }
    if (!supported) return null
    val contentOrigin = remember { mutableStateOf(Offset.Zero) }
    val config = LocalConfiguration.current
    val isDark = LocalIsDark.current
    val layer = key(config, isDark) { rememberGraphicsLayer() }
    return remember(layer, contentOrigin) { MetalBackdropState(layer, contentOrigin) }
}

/**
 * 内容容器用：录一份可反射副本后再照常绘制（肉眼无变化）。
 * 读取 [clock] 让录制与浮岛同拍——否则子内容重绘不会触发父绘制重跑，副本会停在旧帧。
 * 图层先铺 [baseColor]：页面自身不画底（靠 Scaffold），缺底的图层在浮岛处会被反射着色器采成黑。
 */
fun Modifier.metalBackdropSource(
    backdrop: MetalBackdropState?,
    clock: State<Float>?,
    enabled: Boolean,
    baseColor: Color,
): Modifier =
    if (backdrop == null || !enabled) {
        this
    } else {
        this.drawWithContent {
            if (clock != null) clock.value
            backdrop.layer.record {
                drawRect(baseColor)
                this@drawWithContent.drawContent()
            }
            this@drawWithContent.drawContent()
        }
    }
