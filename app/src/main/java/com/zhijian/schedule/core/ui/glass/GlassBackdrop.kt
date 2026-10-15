package com.zhijian.schedule.core.ui.glass

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
 * 折射背景：根内容在正常绘制的同时被录制进 [layer]（绘制指令，非像素拷贝，零截屏），
 * 浮岛以 AGSL 采样该图层做边缘透镜位移——真实实时折射，滚动同步、可见输出不变。
 */
@Stable
class GlassBackdropState internal constructor(
    val layer: GraphicsLayer,
    /** 内容原点由外部持有：图层随配置重建时原点状态不重置（重置会让折射采样整体错位） */
    private val contentOrigin: MutableState<Offset>,
) {
    /** 内容层左上角在窗口坐标中的位置（px），由内容容器 onGloballyPositioned 写入 */
    var contentOriginInWindow: Offset
        get() = contentOrigin.value
        internal set(value) {
            contentOrigin.value = value
        }

    /** 折射 effect 已挂载时的参数签名（绘制期惰性挂载：挂载前先写入静态 uniform，保证图层已有内容且快照正确） */
    internal var lensAttachSignature: Any? = null

    internal fun onContentPositioned(origin: Offset) {
        contentOriginInWindow = origin
    }
}

/**
 * 玻璃主题 + shader 档才启用；其余组合返回 null 时调用方零开销。
 * 配置变更（深浅色/旋转等）时重建图层：RenderEffect 的图层内容光栅缓存不会随显示列表刷新，
 * 配置切换瞬间若缓存了过渡空帧，折射会永久采样到黑——重建节点回到与冷启动一致的干净状态。
 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean): GlassBackdropState? {
    if (!enabled) return null
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }
    if (!supported) return null
    val contentOrigin = remember { mutableStateOf(Offset.Zero) }
    val config = LocalConfiguration.current
    val isDark = LocalIsDark.current
    val layer = key(config, isDark) { rememberGraphicsLayer() }
    return remember(layer, contentOrigin) { GlassBackdropState(layer, contentOrigin) }
}

/**
 * 内容容器用：录制一份可折射副本后再照常绘制（肉眼无任何变化）。
 * [enabled] 为 false（或 [backdrop] 为 null）时零开销。
 * 注意：子内容重绘不会触发父绘制重跑，若不订阅时钟，录制副本会停在旧帧（滚动/换肤后背景不跟手）——
 * 读取 [clock] 让本绘制随浮岛同拍（~30fps）重跑重录；静止时内容一致，重放命中缓存，代价极小。
 * 图层先铺 [baseColor]：页面自身不画底色（靠 Scaffold 背景），缺底的图层在浮岛处会被折射着色器采成黑。
 */
fun Modifier.glassBackdropSource(
    backdrop: GlassBackdropState?,
    clock: State<Float>?,
    enabled: Boolean,
    baseColor: Color,
): Modifier =
    if (backdrop == null || !enabled) this
    else this.drawWithContent {
        if (clock != null) clock.value
        backdrop.layer.record {
            drawRect(baseColor)
            this@drawWithContent.drawContent()
        }
        this@drawWithContent.drawContent()
    }
