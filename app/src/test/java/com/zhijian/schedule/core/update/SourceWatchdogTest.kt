package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.WATCHDOG_FIRST_BYTE_MS
import com.zhijian.schedule.core.update.WATCHDOG_MIN_SPEED_BPS
import com.zhijian.schedule.core.update.WATCHDOG_SPEED_GRACE_MS
import com.zhijian.schedule.core.update.WATCHDOG_STALL_TIMEOUT_MS
import com.zhijian.schedule.core.update.WatchdogVerdict
import com.zhijian.schedule.core.update.sourceWatchdogVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceWatchdogTest {
    @Test
    fun stallIsDetectedEvenVeryEarlyInAttempt() {
        assertEquals(
            WatchdogVerdict.Stalled,
            sourceWatchdogVerdict(
                movedBytes = 0,
                stillForMs = WATCHDOG_STALL_TIMEOUT_MS,
                elapsedMs = 40_000,
                hasNextSource = true,
            ),
        )
    }

    @Test
    fun activeTransferIsOk() {
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 5_000_000,
                stillForMs = 1_000,
                elapsedMs = 90_000,
                hasNextSource = true,
            ),
        )
    }

    @Test
    fun slowSourceSwitchesOnlyWhenAnotherSourceExists() {
        assertEquals(
            WatchdogVerdict.TooSlow,
            sourceWatchdogVerdict(
                movedBytes = 60_000,
                stillForMs = 2_000,
                elapsedMs = 90_000,
                hasNextSource = true,
            ),
        )
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 60_000,
                stillForMs = 2_000,
                elapsedMs = 90_000,
                hasNextSource = false,
            ),
        )
    }

    @Test
    fun gracePeriodProtectsSlowStart() {
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 1_000,
                stillForMs = 1_000,
                elapsedMs = WATCHDOG_SPEED_GRACE_MS - 1,
                hasNextSource = true,
            ),
        )
    }

    @Test
    fun noFirstByteSwitchesEarlyWhenAnotherSourceExists() {
        // 点下载之后最难受的一段：一个有下一个候选、却一个字节都没进来的源。
        // 旧规则要等 25 s 才判停顿，那 25 s 里界面完全没进度，读作卡死。
        assertEquals(
            WatchdogVerdict.Stalled,
            sourceWatchdogVerdict(
                movedBytes = 0,
                stillForMs = 1_000,
                elapsedMs = WATCHDOG_FIRST_BYTE_MS,
                hasNextSource = true,
            ),
        )
    }

    @Test
    fun noFirstByteWaitsWhenItIsTheOnlySource() {
        // 单源时早换没有意义：换也白换，还可能把一条正在建连的慢线掐掉
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 0,
                stillForMs = 1_000,
                elapsedMs = WATCHDOG_FIRST_BYTE_MS,
                hasNextSource = false,
            ),
        )
    }

    @Test
    fun firstByteArrivalKeepsSource() {
        // 已经有字节在进来就不再算「没有首字节」，交给停滞 / 过慢两条规则
        assertEquals(
            WatchdogVerdict.Ok,
            sourceWatchdogVerdict(
                movedBytes = 512,
                stillForMs = 200,
                elapsedMs = WATCHDOG_FIRST_BYTE_MS,
                hasNextSource = true,
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
