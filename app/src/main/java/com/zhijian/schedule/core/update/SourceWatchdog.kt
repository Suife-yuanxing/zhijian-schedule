package com.zhijian.schedule.core.update

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 源监视器的判决（纯逻辑，便于单测） */
internal sealed interface WatchdogVerdict {
    data object Ok : WatchdogVerdict

    /** 长时间没有新字节：直连被黑洞、连接假死 */
    data object Stalled : WatchdogVerdict

    /** 还能换源，但有效速度长期低于下限——手机网络里「慢但能下」的体感等同不可用 */
    data object TooSlow : WatchdogVerdict
}

/** 25 秒没有新字节视为停滞 */
internal const val WATCHDOG_STALL_TIMEOUT_MS = 25_000L

/** 前 1 分钟是连接/加速宽限期，之后才评价速度 */
internal const val WATCHDOG_SPEED_GRACE_MS = 60_000L

/** 有效速度下限：低于 30KB/s 且还有候选源时换源（46MB 按此速度要 25 分钟以上） */
internal const val WATCHDOG_MIN_SPEED_BPS = 30_000L

/** 源监视器巡检间隔 */
private const val WATCHDOG_TICK_MS = 3_000L

internal fun sourceWatchdogVerdict(
    movedBytes: Long,
    stillForMs: Long,
    elapsedMs: Long,
    hasNextSource: Boolean,
): WatchdogVerdict = when {
    stillForMs >= WATCHDOG_STALL_TIMEOUT_MS -> WatchdogVerdict.Stalled
    hasNextSource && movedBytes > 0L && elapsedMs >= WATCHDOG_SPEED_GRACE_MS &&
        movedBytes * 1000L / elapsedMs.coerceAtLeast(1) < WATCHDOG_MIN_SPEED_BPS -> WatchdogVerdict.TooSlow
    else -> WatchdogVerdict.Ok
}

/**
 * 用源监视器包住一次源尝试：25 秒没有新字节（直连被黑洞、连接假死）或过了宽限期后
 * 有效速度仍低于 30KB/s 且还有候选源时，中止本源的连接交给源链。
 * 「慢 ≠ 失败」仅指首个候选还能稳定产出；持续爬行在手机网络里体感等同不可用。
 */
internal suspend fun <T> DownloadSession.withSourceWatchdog(
    hasNextSource: Boolean,
    attemptStartBytes: Long,
    block: suspend () -> T,
): T = coroutineScope {
    val watchdog = launch(Dispatchers.IO) {
        var lastBytes = publishedBytes
        val startAt = SystemClock.elapsedRealtime()
        var lastMoveAt = startAt
        while (isActive) {
            delay(WATCHDOG_TICK_MS)
            val nowMs = SystemClock.elapsedRealtime()
            // 只看真实进度：稀疏 `.part` 的长度会在换源/空洞时失真
            val bytes = publishedBytes
            if (bytes != lastBytes) {
                lastBytes = bytes
                lastMoveAt = nowMs
            }
            when (sourceWatchdogVerdict(
                movedBytes = bytes - attemptStartBytes,
                stillForMs = nowMs - lastMoveAt,
                elapsedMs = nowMs - startAt,
                hasNextSource = hasNextSource,
            )) {
                WatchdogVerdict.Ok -> Unit
                WatchdogVerdict.Stalled -> {
                    UpdateTrace.log { "源停滞 ${(nowMs - lastMoveAt) / 1000}s 无新字节，中止换源" }
                    sourceAbort.set(true)
                    cancelActiveCalls()
                    break
                }
                WatchdogVerdict.TooSlow -> {
                    UpdateTrace.log {
                        "源过慢（${bytes - attemptStartBytes}B / ${(nowMs - startAt) / 1000}s），中止换源"
                    }
                    sourceAbort.set(true)
                    cancelActiveCalls()
                    break
                }
            }
        }
    }
    try {
        block()
    } finally {
        watchdog.cancel()
    }
}
