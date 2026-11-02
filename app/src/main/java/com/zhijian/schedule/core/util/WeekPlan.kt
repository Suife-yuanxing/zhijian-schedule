package com.zhijian.schedule.core.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「今天」的跨零点驱动。
 *
 * ## 为什么需要它
 * 周次（第 N 周）是**日期**的函数，而各个 ViewModel 把它算在 `combine(...)` 的 transform 里：
 * 上游不发射，transform 就不重跑。课表不变、设置不变、节假日不变时，周日夜里跨到周一，
 * 顶栏会一直停在「本周 · 第 9 周」，直到切页、改设置或进程重启。
 *
 * 把「今天」提成一条自己的流，喂给同一层 combine，周次就跟着自然日走。
 *
 * ## 为什么不是每分钟一条
 * 周次只随**日期**变，一天一跳就够；按分钟推会让课表页每分钟整页重组一次
 * （这正是 [com.zhijian.schedule.core.ui.LocalClock] 当初把 30s 循环收成整分时钟要避免的事）。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object WeekPlan {
    /**
     * 午夜唤醒的余量。
     *
     * 00:00:00 整那一刻边界事件该算前一天还是后一天，各系统口径不一；等一秒再算，
     * 让「落定」先发生，避免跨零点瞬间算出前一天导致 UI 闪一下再翻回来。
     */
    private const val MIDNIGHT_GRACE_MS = 1_000L

    /**
     * 先发当前日期，之后每到本地午夜 + [MIDNIGHT_GRACE_MS] 发一次新日期。
     *
     * 冷启动即发射一次，所以订阅方不必自己补初值；值相同的重复发射被
     * `distinctUntilChanged` 吃掉（时区夏令时回拨等边界也不会重复触发下游）。
     */
    fun todayTicks(zone: ZoneId = ZoneId.systemDefault()): Flow<LocalDate> =
        flow {
            while (true) {
                val now = ZonedDateTime.now(zone)
                emit(now.toLocalDate())
                delay(waitUntilNextDay(now))
            }
        }.distinctUntilChanged()

    /**
     * 距下一个本地午夜（含余量）还有多少毫秒。
     *
     * 抽成纯函数是为了能测：直接测 [todayTicks] 要等真实时间，测这个只要给一个时刻。
     */
    internal fun waitUntilNextDay(now: ZonedDateTime): Long {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val remaining = Duration.between(now, nextMidnight).toMillis()
        return (remaining + MIDNIGHT_GRACE_MS).coerceAtLeast(MIDNIGHT_GRACE_MS)
    }
}
