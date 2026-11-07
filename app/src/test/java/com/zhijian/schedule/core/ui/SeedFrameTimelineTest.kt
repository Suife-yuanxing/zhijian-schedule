package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「两条进度是否严格同帧」这条结论的判定器。
 *
 * 这个累加器存在的意义就是把真机验收里那句「构造上保证了」变成「实测确认了」。
 * 所以它自己的判定必须钉住——判错了会给出一个假阳性结论（说同帧而其实分开了，
 * 或者说分开了而其实没有），两者都会让验收失去价值。
 */
class SeedFrameTimelineTest {
    /** 60Hz 下一个 vsync 周期 */
    private val vsyncStep = 16_666_667L

    /**
     * 模拟**同一条 animate 回调**逐帧推进：每次回调写两个相同的值、代数自增。
     * 采样端滞后一帧读到它们——这正是真实时序。
     */
    private fun sameFrameRun(frames: Int): SeedFrameTimeline {
        val timeline = SeedFrameTimeline()
        var written = 0f
        var seq = 0L
        for (i in 0 until frames) {
            written = (i + 1).toFloat() / frames
            seq++
            timeline.onSample(
                vsyncNanos = i * vsyncStep,
                progress = written,
                seedExit = written,
                writeSeq = seq,
            )
        }
        return timeline
    }

    @Test
    fun `同一个值写两个槽时 每一帧都不算分歧`() {
        val t = sameFrameRun(24)
        assertEquals(24, t.samples)
        assertEquals("同帧推进不能记到任何分歧", 0, t.diverged)
        assertEquals(0f, t.maxDeviation, 1e-9f)
        assertTrue("收口结论必须是 sameFrame=true", t.summary().contains("sameFrame=true"))
    }

    /**
     * 模拟**两条各跑各的协程**：某一帧只有 progress 前进、seedExit 停在上一帧。
     * 判定器必须把这一帧记成分歧，并记住偏差大小。
     */
    @Test
    fun `两条各跑各的时 会记到分歧与偏差`() {
        val t = SeedFrameTimeline()
        var seq = 0L
        // 第 3 帧故意让 seedExit 落后
        val progressValues = listOf(0.1f, 0.2f, 0.4f, 0.5f)
        val seedValues = listOf(0.1f, 0.2f, 0.3f, 0.5f)
        for (i in progressValues.indices) {
            seq++
            t.onSample(
                vsyncNanos = i * vsyncStep,
                progress = progressValues[i],
                seedExit = seedValues[i],
                writeSeq = seq,
            )
        }
        assertEquals(1, t.diverged)
        assertEquals(0.1f, t.maxDeviation, 1e-6f)
        assertTrue("收口结论必须是 sameFrame=false", t.summary().contains("sameFrame=false"))
    }

    /** 容差之内的浮点噪声不算分歧：两边写的是同一个 Float，理论上严格相等 */
    @Test
    fun `极小浮点差不算分歧`() {
        val t = SeedFrameTimeline()
        t.onSample(0L, progress = 0.5f, seedExit = 0.5f + 1e-9f, writeSeq = 1L)
        assertEquals(0, t.diverged)
    }

    /** 掉帧必须被量出来：间隔明显大于刷新周期时 maxGap 要跟着变大 */
    @Test
    fun `掉帧会体现在最大 vsync 间隔上`() {
        val t = SeedFrameTimeline()
        val times = listOf(0L, vsyncStep, vsyncStep * 5, vsyncStep * 6)
        times.forEachIndexed { i, at ->
            t.onSample(at, progress = 0.1f * i, seedExit = 0.1f * i, writeSeq = i.toLong())
        }
        // 中间跳过 3 个 vsync：最大间隔应是 4 个周期
        assertEquals(vsyncStep * 4, t.maxIntervalNanos)
        assertEquals(vsyncStep, t.minIntervalNanos)
        assertTrue("收口里应带上 maxGapMs", t.summary().contains("maxGapMs="))
    }

    /**
     * 写代数只用来数「这一帧有没有新值」，不该因为读到同一个代数就判分歧。
     *
     * 正常节奏下驱动每帧都写（60Hz 动画），所以 framesWithWrite 应接近 samples；
     * 但动画结束后的尾帧可能不再写，那时 progress/seed 都停在终值，
     * 依旧必须是「同帧」。
     */
    @Test
    fun `尾帧不再刷新时 依然是同帧`() {
        val t = SeedFrameTimeline()
        t.onSample(0L, progress = 1f, seedExit = 1f, writeSeq = 7L)
        t.onSample(vsyncStep, progress = 1f, seedExit = 1f, writeSeq = 7L)
        assertEquals(0, t.diverged)
        assertEquals(1, t.framesWithWrite)
        assertTrue(t.summary().contains("sameFrame=true"))
    }

    /** 收口里必须带真实的帧率与时长：这是「方块副本有没有把主线程压垮」的唯一量化出口 */
    @Test
    fun `收口带帧率与时长`() {
        val t = sameFrameRun(25)
        val s = t.summary()
        // 25 帧、24 个间隔 × 16.67ms ≈ 400ms，帧率应接近 60
        assertTrue("spanMs 应接近 400：$s", s.contains("spanMs=400"))
        assertTrue("fps 应接近 60：$s", s.contains("fps=60"))
    }
}
