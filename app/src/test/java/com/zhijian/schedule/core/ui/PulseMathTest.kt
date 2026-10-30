package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「现在」呼吸灯的取值与采样节奏。
 *
 * 这组断言守的是两件事：
 * 1) 亮度曲线与旧实现等价（0.55 → 1.0，1800ms 一个来回，起点最暗）；
 * 2) 采样是**定频**的（15Hz），不是逐帧——课表页静置 62fps 重绘的根因就在这里。
 */
class PulseMathTest {
    @Test
    fun startsAtDarkest() {
        assertEquals(PulseMath.MIN_ALPHA, PulseMath.alpha(0L), 1e-4f)
    }

    @Test
    fun brightestAtHalfPeriod() {
        assertEquals(PulseMath.MAX_ALPHA, PulseMath.alpha(PulseMath.PERIOD_MS / 2), 1e-4f)
    }

    @Test
    fun backToDarkestAtFullPeriod() {
        assertEquals(PulseMath.MIN_ALPHA, PulseMath.alpha(PulseMath.PERIOD_MS), 1e-4f)
    }

    @Test
    fun staysWithinRange() {
        var t = 0L
        while (t <= PulseMath.PERIOD_MS * 2) {
            val a = PulseMath.alpha(t)
            assertTrue("t=$t a=$a", a >= PulseMath.MIN_ALPHA - 1e-4f && a <= PulseMath.MAX_ALPHA + 1e-4f)
            t += 7L
        }
    }

    @Test
    fun negativeTimeIsClampedToZero() {
        assertEquals(PulseMath.alpha(0L), PulseMath.alpha(-500L), 1e-4f)
    }

    @Test
    fun adjacentSamplesStepIsImperceptible() {
        // 相邻两次采样的亮度差要小到看不出跳格（< 5%），最陡处是曲线中点附近
        var t = 0L
        var maxStep = 0f
        while (t < PulseMath.PERIOD_MS) {
            val d = abs(PulseMath.alpha(t + PulseMath.SAMPLE_MS) - PulseMath.alpha(t))
            if (d > maxStep) maxStep = d
            t += 25L
        }
        assertTrue("max step=$maxStep", maxStep < 0.05f)
    }

    @Test
    fun sampleRateIsTwentyHertz() {
        // 20Hz：1000 / 50 = 20 次每秒；相对旧的「逐帧 60Hz」是 1/3 的重绘量
        assertEquals(20.0, 1000.0 / PulseMath.SAMPLE_MS, 0.01)
        // 一个来回至少 30 个采样点，否则呼吸会被读成阶梯
        assertTrue(PulseMath.PERIOD_MS / PulseMath.SAMPLE_MS >= 30L)
    }

    @Test
    fun nextDelayNeverExceedsSampleInterval() {
        var t = 0L
        while (t < 5000L) {
            val d = PulseMath.nextDelayMs(t)
            assertTrue("t=$t d=$d", d in 1L..PulseMath.SAMPLE_MS)
            t += 13L
        }
    }
}
