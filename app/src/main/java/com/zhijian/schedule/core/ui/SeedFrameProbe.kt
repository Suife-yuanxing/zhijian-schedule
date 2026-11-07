package com.zhijian.schedule.core.ui

import android.util.Log
import android.view.Choreographer
import com.zhijian.schedule.BuildConfig

/**
 * 用 **Choreographer 的 vsync 时间戳**给「方块离场与页面外扩同时进行」做逐帧取证。
 *
 * ## 它回答的是哪一类问题
 *
 * 10x 慢放下的 `screencap` 连拍能回答「屏幕上是不是同时动了」，但它回答不了下面三条：
 *
 * 1. 两条进度是否**严格同帧**——连拍既没有 vsync 时间戳，也可能跳过帧；
 * 2. 真实帧率——掉帧的那一帧被 `screencap` 抓到只会看起来「跳了一下」，量不出间隔；
 * 3. 方块副本有没有额外开销——`SeedOverlay` 每帧多画一次圆角矩形 + 两条笔画，
 *    如果它把主线程压过 16.6ms，这里会直接看到 vsync 间隔变大。
 *
 * ## 为什么挂在 Choreographer 上而不是 `withFrameNanos`
 *
 * `Choreographer.postFrameCallback` 的 `frameTimeNanos` 就是这一帧的 **vsync 时间**，
 * 即屏幕真正呈现它的时刻；这在系统层面是权威值，而 `withFrameNanos` 给的是 Compose 的
 * 帧时钟，两者在正常路径上一致、在被跳过的帧上语义不同（前者不会跳过、只会迟到）。
 * 需要量「真实掉帧」时，只能信前者。
 *
 * ## 采样滞后一帧，不影响结论
 *
 * vsync N 读到的是**上一帧回调写下**的值。若两条进度曾分属不同帧，那么它们之间必然存在
 * 某个样本读到「一个已更新、一个是旧值」，[SeedFrameTimeline.diverged] 就会记到。
 * 反过来说，只要 diverged 恒为 0，就证明它们从来没有分开过。
 *
 * ## 只在 debug 包生效
 *
 * `BuildConfig.DEBUG` 是编译期常量，R8 会把整条分支从正式包里删掉，线上零开销。
 * 关闭时 [watch] 直接返回，连回调都不注册。
 */
internal object SeedFrameProbe {
    private const val TAG = "SeedFrame"

    private val enabled = BuildConfig.DEBUG

    private var timeline: SeedFrameTimeline? = null
    private var posted = false

    /**
     * 开始一轮采样。[tracking] 为 false（换主题 / 反向 / 没有方块副本）时什么都不做：
     * 那些路径上只有一条进度，「两条是否同帧」这个命题不成立。
     *
     * 取值用 lambda 而不是快照：采样发生在帧回调里，必须读到**此刻**的值。
     */
    fun watch(
        tracking: Boolean,
        progressOf: () -> Float,
        seedOf: () -> Float,
        writeSeqOf: () -> Long,
    ) {
        if (!enabled || !tracking) return
        val fresh = SeedFrameTimeline()
        timeline = fresh
        if (posted) return
        posted = true
        val choreographer = Choreographer.getInstance()
        val callback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val current = timeline
                    if (current == null) {
                        posted = false
                        return
                    }
                    val line =
                        current.onSample(
                            vsyncNanos = frameTimeNanos,
                            progress = progressOf(),
                            seedExit = seedOf(),
                            writeSeq = writeSeqOf(),
                        )
                    Log.d(TAG, line)
                    choreographer.postFrameCallback(this)
                }
            }
        choreographer.postFrameCallback(callback)
    }

    /** 一轮结束：输出收口结论，并停掉采样 */
    fun stop() {
        if (!enabled) return
        val current = timeline ?: return
        timeline = null
        Log.i(TAG, current.summary())
    }
}
