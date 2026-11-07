package com.zhijian.schedule.core.ui

import java.util.Locale
import kotlin.math.abs

/**
 * 一段「方块离场与页面外扩是否同帧」的逐帧采样统计（纯逻辑，无 Android 依赖，可进本机单测）。
 *
 * ## 为什么需要它
 *
 * 同时离场的实现把 `progress` 与 `seedExit` 写在**同一个 animate 回调**里，所以「逐帧对齐」
 * 是构造出来的性质。但「构造上保证了」与「实测确认了」是两件事——本轮真机验收用的是
 * 10x 慢放下的 screencap 连拍，那是「抓到哪帧算哪帧」的定性证据，没有 vsync 时间戳，
 * 既证明不了两条进度同帧，也测不出真实掉帧。
 *
 * 这个累加器负责把后者补上：每次 [onSample] 收到一个 vsync 的采样，就检查两个值是否一致、
 * 并记录相邻 vsync 的真实间隔。只要 [diverged] 恒为 0，就说明两条进度**从来没有分开过**。
 *
 * 注意采样的时序：vsync N 读到的值是**上一帧回调写下**的，所以采样永远滞后一帧。
 * 这不影响结论——如果两条进度曾分属不同帧，那么在它们之间必然存在某个 vsync 样本读到
 * 「一个已更新、一个还是旧值」，[diverged] 就会记到。
 */
internal class SeedFrameTimeline {
    /** 采样到的 vsync 帧数 */
    var samples: Int = 0
        private set

    /** 其中两个值不一致的帧数（结论要求恒为 0） */
    var diverged: Int = 0
        private set

    /** 两个值的最大偏差（结论要求恒为 0） */
    var maxDeviation: Float = 0f
        private set

    /** 相邻 vsync 的最短间隔（ns）：正常应接近屏幕刷新周期 */
    var minIntervalNanos: Long = Long.MAX_VALUE
        private set

    /** 相邻 vsync 的最长间隔（ns）：明显大于刷新周期即为一次掉帧 */
    var maxIntervalNanos: Long = 0L
        private set

    /** 最近一次见到的写代数：驱动每次写两条进度后自增，用它区分「这一帧有没有新值」 */
    var lastWriteSeq: Long = -1L
        private set

    /** 采样到的帧里，写下新值的帧数 */
    var framesWithWrite: Int = 0
        private set

    private var started = false
    private var firstNanos = 0L
    private var lastNanos = 0L

    /**
     * 采一帧。返回可直接写进 logcat 的一行（调用方决定要不要输出）。
     *
     * [vsyncNanos] 是 `Choreographer.FrameCallback` 给出的帧时间，即屏幕真正呈现这一帧的时刻；
     * [writeSeq] 是驱动侧的写代数，用来判断这一帧有没有被新值刷新。
     */
    fun onSample(
        vsyncNanos: Long,
        progress: Float,
        seedExit: Float,
        writeSeq: Long,
    ): String {
        samples++
        trackInterval(vsyncNanos)
        if (writeSeq != lastWriteSeq) {
            lastWriteSeq = writeSeq
            framesWithWrite++
        }
        val gap = abs(progress - seedExit)
        if (gap > DEVIATION_EPSILON) {
            diverged++
            if (gap > maxDeviation) maxDeviation = gap
        }
        return "frame=" + samples +
            " t=+" + millis(relativeNanos(vsyncNanos) / NANOS_PER_MILLI) + "ms" +
            " vsync=" + vsyncNanos +
            " progress=" + fixed(progress) +
            " seed=" + fixed(seedExit) +
            " d=" + fixed(gap) +
            " writeSeq=" + writeSeq
    }

    /** 一段采样的收口结论：同帧与否、真实帧率、有没有掉帧 */
    fun summary(): String {
        val sameFrame = diverged == 0 && samples > 0
        val spanMs = if (started) (lastNanos - firstNanos) / NANOS_PER_MILLI else 0.0
        val fps = if (spanMs > 0.0 && samples > 1) (samples - 1) * MILLIS_PER_SECOND / spanMs else 0.0
        val minGap = if (minIntervalNanos == Long.MAX_VALUE) 0.0 else minIntervalNanos / NANOS_PER_MILLI
        return "summary samples=" + samples +
            " sameFrame=" + sameFrame +
            " diverged=" + diverged +
            " maxDeviation=" + fixed(maxDeviation) +
            " framesWithWrite=" + framesWithWrite +
            " spanMs=" + millis(spanMs) +
            " fps=" + millis(fps) +
            " minGapMs=" + millis(minGap) +
            " maxGapMs=" + millis(maxIntervalNanos / NANOS_PER_MILLI)
    }

    private fun trackInterval(vsyncNanos: Long) {
        if (!started) {
            started = true
            firstNanos = vsyncNanos
        } else {
            val gap = vsyncNanos - lastNanos
            if (gap > 0) {
                if (gap < minIntervalNanos) minIntervalNanos = gap
                if (gap > maxIntervalNanos) maxIntervalNanos = gap
            }
        }
        lastNanos = vsyncNanos
    }

    private fun relativeNanos(vsyncNanos: Long): Long = if (started) vsyncNanos - firstNanos else 0L

    private fun millis(nanos: Double): String = String.format(Locale.US, "%.1f", nanos)

    private fun fixed(value: Float): String = String.format(Locale.US, "%.4f", value)

    private companion object {
        /** 判定「两个值算同一时刻」的容差：两边写的是同一个 Float，理论上严格相等 */
        const val DEVIATION_EPSILON = 1e-6f

        const val NANOS_PER_MILLI = 1_000_000.0

        const val MILLIS_PER_SECOND = 1000.0
    }
}
