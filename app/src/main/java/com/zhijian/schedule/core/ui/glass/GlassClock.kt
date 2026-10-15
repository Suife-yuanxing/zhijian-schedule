package com.zhijian.schedule.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * 玻璃流动时钟（秒）：30fps 量化 + 生命周期暂停（非 RESUMED 不请求帧，恢复时从 0 重新计时）。
 * 仅玻璃浮岛在玻璃主题下调用它——其余页面零开销。
 */
@Composable
fun rememberGlassClock(): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        while (currentCoroutineContext().isActive) {
            snapshotFlow { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                .first { it }
            var base = 0L
            var last = 0L
            withFrameNanos {
                base = it
                last = it
            }
            while (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val now = withFrameNanos { it }
                if (now - last >= 33_000_000L) {
                    last = now
                    time.floatValue = (now - base) / 1_000_000_000f
                }
            }
        }
    }
    return time
}
