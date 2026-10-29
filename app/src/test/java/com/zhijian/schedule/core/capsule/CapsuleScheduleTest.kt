package com.zhijian.schedule.core.capsule

import com.zhijian.schedule.core.capsule.CapsulePlan
import com.zhijian.schedule.core.capsule.buildCapsulePlan
import com.zhijian.schedule.core.capsule.countdownCleanupDelayMillis
import com.zhijian.schedule.core.capsule.nextRefreshAtMillis
import com.zhijian.schedule.core.capsule.nextTransitionAtMillis
import com.zhijian.schedule.core.capsule.reminderMilestones
import com.zhijian.schedule.core.capsule.shouldUseAlarmClockFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 胶囊「下一次什么时候再算」的断言：刷新心跳、状态切换时刻、倒计时清理截止、
 * 系统闹钟降级策略。状态本身的断言在 [CapsulePlanTest]。
 */
class CapsuleScheduleTest : CapsulePlanFixtures() {
    // ---------- 刷新心跳与切换时刻 ----------

    @Test
    fun refreshBeatSecuresLiveStatesAndIdleStates() {
        // 上课中且切换还很远（11:45）：心跳 = now + 6min，课堂期间持续保鲜
        val ongoing =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(epochOf(10, 36), nextRefreshAtMillis(ongoing, tuesday(10, 30)))

        // 即将上课：心跳最多 6 分钟后（10:01），入场后的第一次保活
        val about =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 1), nextRefreshAtMillis(about, tuesday(9, 55)))

        // 即将开课（距离切换 < 5min）：心跳 = 切换 + 1min，切换丢失 1 分钟内自愈
        val aboutNear =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 6),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 11), nextRefreshAtMillis(aboutNear, tuesday(10, 6)))

        // 已结束：心跳 = 次日 00:02，切换（跨天）丢失 2 分钟内自愈，平时不打扰
        val done =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.DayDone
        val beat =
            LocalDateTime
                .of(2026, 10, 14, 0, 2)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        assertEquals(beat, nextRefreshAtMillis(done, tuesday(12, 30)))

        assertNull(nextRefreshAtMillis(CapsulePlan.NoTable, tuesday(10, 30)))
    }

    @Test
    fun countdownCleanupDeadlineFollowsClassPhases() {
        val courses = listOf(course(day = 2, 3, 4))
        // 上课中：距下课 75 分钟 + 10s 宽限（系统在延时到点后撤下卡片，闹钟被吞也不停在 00:00）
        val ongoing =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(
            75 * 60_000L + 10_000L,
            countdownCleanupDelayMillis(ongoing, epochOf(10, 30)),
        )

        // 即将上课：距开课 15 分钟 + 40s 宽限（给进程存活时的正常切换留出刷新窗口）
        val about =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        assertEquals(
            15 * 60_000L + 40_000L,
            countdownCleanupDelayMillis(about, epochOf(9, 55)),
        )

        // 非倒计时状态不设兜底
        val done =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.DayDone
        assertNull(countdownCleanupDelayMillis(done, epochOf(12, 30)))
        assertNull(countdownCleanupDelayMillis(CapsulePlan.NoTable, epochOf(12, 30)))
    }

    @Test
    fun countdownCleanupNeverGoesNonPositive() {
        // 时钟边界（渲染恰好落在时刻线之后）：只保留宽限期，不允许 ≤0
        val ongoing =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(11, 44),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(10_000L, countdownCleanupDelayMillis(ongoing, epochOf(11, 50)))
    }

    @Test
    fun aboutToStartChipShowsRemainingMinutes() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 5),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        assertEquals(5, plan.minutesUntil)
        assertEquals("还有 5 分", plan.chip)
    }

    @Test
    fun upcomingGetsPreTransitionWake() {
        // 08:00 时下节课 10:10，倒数窗口 09:40：心跳应在 09:25 先唤醒，
        // 好让 Coordinator 把 09:40 的切换升级成系统闹钟兜底
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(8, 0),
                reminderMinutes = 30,
            ) as CapsulePlan.Upcoming
        assertEquals(epochOf(9, 25), nextRefreshAtMillis(plan, tuesday(8, 0)))
    }

    @Test
    fun alarmClockFallbackOnlyNearTransitionAndWhenEnabled() {
        val now = epochOf(10, 0)
        // 15 分钟内 + 开关开启 + 有精确闹钟权限 → 用闹钟级兜底
        assertTrue(
            shouldUseAlarmClockFallback(
                now,
                now + 15 * 60_000L,
                alarmFallbackEnabled = true,
                exactAlarmsAllowed = true,
            ),
        )
        // 开关关闭 → 不用闹钟图标
        assertEquals(
            false,
            shouldUseAlarmClockFallback(
                now,
                now + 5 * 60_000L,
                alarmFallbackEnabled = false,
                exactAlarmsAllowed = true,
            ),
        )
        // 距离切换还早 → 普通精确闹钟
        assertEquals(
            false,
            shouldUseAlarmClockFallback(
                now,
                now + 16 * 60_000L,
                alarmFallbackEnabled = true,
                exactAlarmsAllowed = true,
            ),
        )
        // 没有精确闹钟权限 → 无法调用 setAlarmClock
        assertEquals(
            false,
            shouldUseAlarmClockFallback(
                now,
                now + 5 * 60_000L,
                alarmFallbackEnabled = true,
                exactAlarmsAllowed = false,
            ),
        )
        // 时刻已过 → 不兜底
        assertEquals(
            false,
            shouldUseAlarmClockFallback(now, now - 1L, alarmFallbackEnabled = true, exactAlarmsAllowed = true),
        )
    }

    @Test
    fun upcomingKnowsWhenCountdownWindowOpens() {
        // 课前 30 分钟提醒：09:40 是进入倒数窗口的时刻，胶囊切换闹钟按它登记
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(8, 0),
                reminderMinutes = 30,
            ) as CapsulePlan.Upcoming
        assertEquals(epochOf(9, 40), plan.aboutToStartAtMillis)
        assertEquals(epochOf(9, 40), nextTransitionAtMillis(plan, tuesday(8, 0)))
    }

    @Test
    fun upcomingWithoutReminderTransitionsAtClassStart() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 0,
            ) as CapsulePlan.Upcoming
        assertNull(plan.aboutToStartAtMillis)
        assertEquals(epochOf(10, 10), nextTransitionAtMillis(plan, tuesday(9, 55)))
    }

    @Test
    fun nextTransitionFollowsClassPhases() {
        val courses = listOf(course(day = 2, 3, 4))
        val ongoing =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        // 10:10–11:45 的课，主提醒节点在剩余 30 分钟处（11:15）
        assertEquals(epochOf(11, 15), nextTransitionAtMillis(ongoing, tuesday(10, 30)))

        val about =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 10), nextTransitionAtMillis(about, tuesday(9, 55)))

        val done =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.DayDone
        val midnight =
            LocalDateTime
                .of(2026, 10, 14, 0, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        assertEquals(midnight, nextTransitionAtMillis(done, tuesday(12, 30)))

        val holiday =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
                holidayName = "国庆节",
            ) as CapsulePlan.Holiday
        assertEquals(midnight, nextTransitionAtMillis(holiday, tuesday(10, 30)))

        assertNull(nextTransitionAtMillis(CapsulePlan.NoTable, tuesday(10, 30)))
    }
}
