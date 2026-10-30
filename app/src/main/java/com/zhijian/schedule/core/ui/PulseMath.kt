package com.zhijian.schedule.core.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * 「现在」呼吸灯的取值与采样节奏（纯逻辑，可在 JVM 单测里钉住）。
 *
 * ## 为什么需要它（性能）
 * 旧实现用 `rememberInfiniteTransition` + `animateFloat(0.55f..1f, 1800ms, Reverse)`：
 * 无限动画**每一帧**都会让读取它的绘制失效，于是课表页只要含有「现在线」，
 * 静置时就恒定 60fps 重绘——真机实测 10 秒画了 621 帧（≈62fps），
 * 而今日页 / 我的页同一时段是 0 帧。这不只是耗电：切页那一刻整屏滑动的每一帧
 * 都要和这条全天候的呼吸灯抢同一个 GPU / RenderThread，实测切页的 legacy jank
 * 长期停在 42~52%。
 *
 * ## 做法：把「无限动画」换成「定频采样」
 * 呼吸灯的语义是「慢慢亮、慢慢暗」，1800ms 一个来回，肉眼看不出 60fps 与 15fps 的差别
 * （每帧幅度不到 1%），但重绘次数降到 1/4。取值改成由经过时间直接算出（余弦），
 * 于是它是**可重放的纯函数**：随便跳到哪个时刻都能得到正确亮度，不依赖动画引擎的累积状态。
 *
 * 采样频率取 20Hz：一个来回 1800ms 内有 36 个采样点，最陡处亮度台阶约 3.9%，
 * 落在「看得出来在呼吸、看不出在跳格」的区间（推导见 [SAMPLE_MS]）。
 */
internal object PulseMath {
    /** 呼吸一个来回的时长（毫秒），与旧实现的 1800ms 一致 */
    internal const val PERIOD_MS = 1800L

    /**
     * 采样间隔（毫秒）：20Hz。
     *
     * 取 50 而不是更省电的 66（15Hz），是因为亮度台阶要按最陡处算：
     * 亮度是 `0.55 + 0.45·(1−cos(2πt/T))/2`，导数峰值 `0.45·π/T` ⇒ 一步的最大变化
     * = 0.45·π·SAMPLE_MS/1800。50ms 时约 3.9%，66ms 时 5.2%——后者在暗端已经能被读成跳格。
     * 20Hz 相对旧的「逐帧 60Hz」仍是 1/3 的重绘量，而这一点点省电换不来可见的台阶。
     */
    internal const val SAMPLE_MS = 50L

    /** 最暗 / 最亮的不透明度：与旧实现的 initialValue / targetValue 一致 */
    internal const val MIN_ALPHA = 0.55f
    internal const val MAX_ALPHA = 1f

    /**
     * 经过 [elapsedMs] 毫秒时的呼吸亮度。
     *
     * 从 [MIN_ALPHA] 出发、走向 [MAX_ALPHA] 再回来，用 1 - cos 的半个周期表达，
     * 于是 t=0 恰好是最暗（与旧实现的起点一致），t=PERIOD/2 最亮。
     */
    internal fun alpha(elapsedMs: Long): Float {
        val phase = (elapsedMs.coerceAtLeast(0L) % PERIOD_MS).toDouble() / PERIOD_MS
        val wave = (1.0 - sin(PI / 2 + PI * 2 * phase)) / 2.0
        return (MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * wave).toFloat()
    }

    /** 下一步采样要等多久（毫秒）：把采样点对齐到 PERIOD_MS 的整数倍，避免长期漂移 */
    internal fun nextDelayMs(elapsedMs: Long): Long {
        val next = ((elapsedMs / SAMPLE_MS) + 1) * SAMPLE_MS
        return (next - elapsedMs).coerceIn(1L, SAMPLE_MS)
    }
}
