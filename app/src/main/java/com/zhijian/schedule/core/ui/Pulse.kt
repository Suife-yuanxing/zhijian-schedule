package com.zhijian.schedule.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 「现在」呼吸灯的不透明度：**定频采样**，不是无限动画。
 *
 * 旧实现（记得别再回去）：`rememberInfiniteTransition().animateFloat(0.55f..1f, 1800ms, Reverse)`。
 * 那是一个逐帧推进的无限动画——只要「现在线」在屏幕上，它就让绘制**每一帧**失效，
 * 于是课表页静置也恒定 60fps 重绘（真机实测 10 秒 621 帧 ≈ 62fps，今日页 / 我的页同期 0 帧）。
 * 代价落在切页上：整屏滑动的每一帧都要和这条全天候的呼吸灯抢同一个渲染管线，
 * 实测切页的 legacy jank 长期在 42~52%。
 *
 * 现在换成「按经过时间算值 + 20Hz 唤醒」（见 [PulseMath]）：亮度曲线不变（1800ms 一个来回），
 * 但重绘次数降到 1/3，且不依赖动画引擎——被重建、被取消、切页打断都能从当前时刻接上。
 *
 * 只读它的人应当在**绘制阶段**读（`drawBehind { }` 里），那样每次采样只失效绘制，
 * 不触发重组；在组合期读会让读取它的那棵树每次采样重组一次。
 *
 * 系统关掉动画时不启动循环，直接给一个略高于最暗固定值——「现在几点」是事实，
 * 不该因为关动画而消失，只是不再闪（WCAG 2.3.3 的前庭友好处理）。
 */
@Composable
fun rememberBreathingAlpha(animationsDisabled: Boolean = false): State<Float> {
    val alpha = remember { mutableFloatStateOf(if (animationsDisabled) STATIC_ALPHA else PulseMath.MIN_ALPHA) }
    if (animationsDisabled) return alpha
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        var elapsed = 0L
        while (currentCoroutineContext().isActive) {
            // 非 RESUMED 不推进也不重绘：息屏 / 切走时这条线本来看不见
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                delay(PulseMath.PERIOD_MS)
                continue
            }
            alpha.floatValue = PulseMath.alpha(elapsed)
            delay(PulseMath.SAMPLE_MS)
            elapsed += PulseMath.SAMPLE_MS
        }
    }
    return alpha
}

/** 关掉动画时的固定不透明度：比起伏的最低值亮一点，保证仍然一眼可见 */
private const val STATIC_ALPHA = 0.78f
