package com.zhijian.schedule.core.ui.metal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 金属呼吸时钟（秒）。
 *
 * 与玻璃时钟的差别：玻璃每 33ms 量化一次（30fps，省电优先）；金属要「氧化膜虹彩缓慢漂移 +
 * 触摸光斑跟随 + 环境光雾呼吸」，用户明确要求不考虑性能、要极致效果，所以这里**逐帧**推进，
 * 60fps 跟随刷新率，不做量化。仍然遵守生命周期：非 RESUMED 不请求帧。
 */
@Composable
fun rememberMetalClock(): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        var base = 0L
        var first = true
        while (currentCoroutineContext().isActive) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                // 离开前台就停帧：回来时把基准重置，时间轴不跳变
                withFrameNanos { }
                first = true
                continue
            }
            val now = withFrameNanos { it }
            if (first) {
                base = now
                first = false
            }
            time.floatValue = (now - base) / 1_000_000_000f
        }
    }
    return time
}

/**
 * 全局金属时钟槽位：由 AppRoot 在主题壳里提供一次，全 App 的金属元件共用同一拍。
 * 默认 null（非金属主题）→ 元件自动降档为渐变档，不申请任何帧。
 */
val LocalMetalClock = staticCompositionLocalOf<State<Float>?> { null }

/**
 * 陀螺仪倾角槽位：驱动元件的反射方向与环境的柔光漂移。
 * 倾斜手机时，金属上的高光会像真实金属一样滑过表面——这是金属与「贴图」的分界。
 */
val LocalMetalGyro = staticCompositionLocalOf<State<Offset>?> { null }
