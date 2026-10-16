package com.zhijian.schedule

import com.zhijian.schedule.update.WATCHDOG_MIN_SPEED_BPS
import com.zhijian.schedule.update.WATCHDOG_SPEED_GRACE_MS
import com.zhijian.schedule.update.WATCHDOG_STALL_TIMEOUT_MS
import com.zhijian.schedule.update.WatchdogVerdict
import com.zhijian.schedule.update.sourceWatchdogVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceWatchdogTest {

    @Test
    fun stallIsDetectedEvenVeryEarlyInAttempt() {
        assertEquals(
            WatchdogVerdict.Stalled,
            sourceWatchdogVerdict(
                movedBytes = 0, stillForMs = WATCHDOG_STALL_TIMEOUT_MS,
                elapsedMs = 40_000, hasNextSource = true,
            ),
        )
    }

    @Test
    fun activeTransferIsOk() {
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 5_000_000, stillForMs = 1_000,
                elapsedMs = 90_000, hasNextSource = true,
            ),
        )
    }

    @Test
    fun slowSourceSwitchesOnlyWhenAnotherSourceExists() {
        assertEquals(
            WatchdogVerdict.TooSlow,
            sourceWatchdogVerdict(
                movedBytes = 60_000, stillForMs = 2_000,
                elapsedMs = 90_000, hasNextSource = true,
            ),
        )
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 60_000, stillForMs = 2_000,
                elapsedMs = 90_000, hasNextSource = false,
            ),
        )
    }

    @Test
    fun gracePeriodProtectsSlowStart() {
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 1_000, stillForMs = 1_000,
                elapsedMs = WATCHDOG_SPEED_GRACE_MS - 1, hasNextSource = true,
            ),
        )
    }

    @Test
    fun speedExactlyAtLimitIsOk() {
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = WATCHDOG_MIN_SPEED_BPS * (WATCHDOG_SPEED_GRACE_MS / 1000),
                stillForMs = 1_000,
                elapsedMs = WATCHDOG_SPEED_GRACE_MS,
                hasNextSource = true,
            ),
        )
    }
}
