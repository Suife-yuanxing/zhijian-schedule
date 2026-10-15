package com.zhijian.schedule

import com.zhijian.schedule.update.ProgressThrottle
import com.zhijian.schedule.update.SpeedMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun speedIsNullUntilTwoSamples() {
        val meter = SpeedMeter()
        assertNull(meter.bytesPerSecond())
        meter.record(0L, 0L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun speedIsNullWhenSamplesShareTimestamp() {
        val meter = SpeedMeter()
        meter.record(1_000L, 0L)
        meter.record(1_000L, 500L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun speedUsesOnlySamplesInsideWindow() {
        val meter = SpeedMeter(windowMillis = 2_000L)
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        meter.record(3_000L, 3_000L) // 窗口下限 1000，最早那个点被挤出去
        // 窗口内只剩 (1000,1000) 与 (3000,3000)：2 秒 2000 字节
        assertEquals(1_000L, meter.bytesPerSecond())
    }

    @Test
    fun speedNeverDividesByZero() {
        val meter = SpeedMeter()
        meter.record(5_000L, 100L)
        meter.record(5_000L, 100L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun etaIsNullWithoutTotalOrSpeed() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        assertNull(meter.etaSeconds(1_000L, null))
        assertNull(SpeedMeter().etaSeconds(0L, 5_000L))
    }

    @Test
    fun etaRoundsUpAndClampsAtZero() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L) // 1000 B/s
        assertEquals(4L, meter.etaSeconds(1_000L, 5_000L))
        assertEquals(1L, meter.etaSeconds(500L, 1_001L))
        assertEquals(0L, meter.etaSeconds(9_000L, 5_000L))
    }

    @Test
    fun etaIsNullWhenSpeedIsZero() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 0L)
        assertNull(meter.etaSeconds(0L, 5_000L))
    }

    @Test
    fun resetClearsSamples() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        meter.reset()
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun throttleAllowsFirstUpdate() {
        assertTrue(ProgressThrottle().shouldRefresh(0L, 1))
    }

    @Test
    fun throttleSkipsUnchangedPercent() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertEquals(false, throttle.shouldRefresh(10_000L, 1))
    }

    @Test
    fun throttleSkipsUpdatesInsideInterval() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertEquals(false, throttle.shouldRefresh(100L, 2))
        assertTrue(throttle.shouldRefresh(500L, 2))
    }

    @Test
    fun throttleRefreshesIndeterminateProgressOnTimeOnly() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, null))
        assertEquals(false, throttle.shouldRefresh(200L, null))
        assertTrue(throttle.shouldRefresh(500L, null))
    }

    @Test
    fun throttleForceBypassesBothConditions() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertTrue(throttle.shouldRefresh(1L, 1, force = true))
        assertEquals(false, throttle.shouldRefresh(2L, 1))
        assertTrue(throttle.shouldRefresh(502L, 2))
    }
}
