package com.zhijian.schedule

import com.zhijian.schedule.capsule.CapsuleNotifier
import com.zhijian.schedule.capsule.CapsulePlan
import com.zhijian.schedule.capsule.buildCapsulePlan
import com.zhijian.schedule.capsule.countdownCleanupDelayMillis
import com.zhijian.schedule.capsule.nextRefreshAtMillis
import com.zhijian.schedule.capsule.nextTransitionAtMillis
import com.zhijian.schedule.capsule.reminderMilestones
import com.zhijian.schedule.capsule.shouldUseAlarmClockFallback
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.WeekMask
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsulePlanTest {

    // 2026-08-31 是周一；2026-10-13 是第 7 周（单周）的周二
    private fun timetable() = TimetableEntity(
        id = 1, name = "测试课表", startDate = "2026-08-31", totalWeeks = 20,
    )

    private fun sections(): List<SectionTimeEntity> {
        val times = listOf(
            Triple(1, "08:20", "09:05"), Triple(2, "09:10", "09:55"),
            Triple(3, "10:10", "10:55"), Triple(4, "11:00", "11:45"),
            Triple(5, "14:20", "15:05"), Triple(6, "15:10", "15:55"),
            Triple(7, "16:05", "16:50"), Triple(8, "16:55", "17:40"),
            Triple(9, "18:20", "19:05"), Triple(10, "19:10", "19:55"),
            Triple(11, "20:00", "20:45"), Triple(12, "20:50", "21:35"),
        )
        return times.map { (no, s, e) ->
            SectionTimeEntity(
                modeId = 1, sectionNo = no, periodNo = (no + 1) / 2,
                session = if (no <= 4) "AM" else "PM", startTime = s, endTime = e,
            )
        }
    }

    private fun course(
        day: Int,
        startSection: Int,
        endSection: Int,
        weeks: Collection<Int> = (1..20).toList(),
        name: String = "课程",
        room: String = "8A206",
        teacher: String = "张老师",
        color: Int = 0xFF4285F4.toInt(),
    ) = CourseEntity(
        id = 0, tableId = 1, name = name, room = room, teacher = teacher,
        dayOfWeek = day, startSection = startSection, endSection = endSection,
        weekMask = WeekMask.fromWeeks(20, weeks), color = color,
    )

    private fun tuesday(h: Int, m: Int) = LocalDateTime.of(2026, 10, 13, h, m)

    @Test
    fun ongoingMidClassComputesProgress() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4, name = "毛泽东思想", room = "8B101")),
            sections(), tuesday(10, 30), reminderMinutes = 30,
        )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("毛泽东思想", ongoing.course.name)
        assertEquals(78, ongoing.progress) // 10:10→11:45 共 5700s，剩余 4500s
        assertEquals(75, ongoing.remainingMinutes)
        assertEquals("剩 75 分", ongoing.chip)
        assertEquals("第3-4节", ongoing.course.sectionText)
        assertEquals("张老师", ongoing.course.teacher)
        assertEquals(2, ongoing.course.sectionCount)
        assertEquals(listOf(45L, 45L), ongoing.course.sectionMinutes)
    }

    @Test
    fun ongoingLastMinuteShowsFinalChip() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(),
            LocalDateTime.of(2026, 10, 13, 11, 44, 30), reminderMinutes = 30,
        )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals(0, ongoing.remainingMinutes) // 不足 1 分钟按 0 计
        assertEquals("即将下课", ongoing.chip)
    }

    @Test
    fun ongoingProgressDepletesOverTime() {
        val early = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 20),
            reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        val late = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(11, 20),
            reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertTrue(early.progress > late.progress) // 倒着消耗：越上课剩余占比越小
        assertEquals(89, early.progress) // 剩余 5100s / 5700s
        assertEquals(26, late.progress) // 剩余 1500s / 5700s
    }

    @Test
    fun ongoingCarriesEndInstantForSystemCountdown() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 30),
            reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        val expected = LocalDateTime.of(2026, 10, 13, 11, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, plan.endAtMillis)
    }

    @Test
    fun reminderMilestonesAdaptToCourseLength() {
        assertEquals(listOf(15L, 5L), reminderMilestones(45))
        assertEquals(listOf(30L, 5L), reminderMilestones(95))
        assertEquals(listOf(40L, 5L), reminderMilestones(120))
        assertEquals(listOf(60L, 5L), reminderMilestones(180))
        // 短课主节点会落到课堂前半段，只保留最后 5 分钟
        assertEquals(listOf(5L), reminderMilestones(15))
        assertTrue(reminderMilestones(5).isEmpty())
    }

    @Test
    fun ongoingAtReminderNodeSwitchesToReminderCopy() {
        val courses = listOf(course(day = 2, 3, 4, name = "毛泽东思想"))
        // 10:10–11:45：剩余 30 分钟节点在 11:15
        val atNode = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 15), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(30L, atNode.reminderMinutes)
        assertEquals("距下课30分", atNode.chip)
        assertEquals(epochOf(11, 17), atNode.reminderUntilMillis)
        assertEquals(epochOf(11, 40), atNode.nextReminderAtMillis)

        // 2 分钟窗口内打开 App，仍应恢复到提醒态
        val withinWindow = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 16), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(30L, withinWindow.reminderMinutes)
    }

    @Test
    fun ongoingReminderCopyMentionsRemainingMinutes() {
        val courses = listOf(course(day = 2, 3, 4, name = "毛泽东思想", room = "8B101"))
        val reminder = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 15), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        val (reminderTitle, reminderText) = CapsuleNotifier.texts(reminder)
        assertEquals("距离下课仅剩 30 分钟", reminderTitle)
        assertEquals("毛泽东思想 · 8B101 · 张老师 · 11:45 下课", reminderText)

        val live = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(10, 30), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        val (liveTitle, liveText) = CapsuleNotifier.texts(live)
        assertEquals("正在上课 · 毛泽东思想", liveTitle)
        assertEquals("8B101 · 张老师 · 11:45 下课 · 还剩 75 分钟", liveText)
    }

    @Test
    fun ongoingReminderRevertsAfterHighlightWindow() {
        val courses = listOf(course(day = 2, 3, 4))
        val after = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 17), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertNull(after.reminderMinutes)
        assertEquals("剩 28 分", after.chip)
        assertEquals(epochOf(11, 40), after.nextReminderAtMillis)
        assertEquals(epochOf(11, 40), nextTransitionAtMillis(after, tuesday(11, 17)))
    }

    @Test
    fun finalFiveMinuteNodeAlsoHighlights() {
        val courses = listOf(course(day = 2, 3, 4))
        val atNode = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 40), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(5L, atNode.reminderMinutes)
        assertEquals("距下课5分", atNode.chip)
        assertEquals(epochOf(11, 42), atNode.reminderUntilMillis)

        val after = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 42), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertNull(after.reminderMinutes)
        assertEquals("剩 3 分", after.chip)
        assertEquals(epochOf(11, 45), nextTransitionAtMillis(after, tuesday(11, 42)))
    }

    @Test
    fun nextTransitionTargetsReminderNodeAndHighlightEnd() {
        val courses = listOf(course(day = 2, 3, 4))
        val beforeNode = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(10, 30), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(epochOf(11, 15), nextTransitionAtMillis(beforeNode, tuesday(10, 30)))

        val atNode = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(11, 15), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(epochOf(11, 17), nextTransitionAtMillis(atNode, tuesday(11, 15)))
    }

    @Test
    fun aboutToStartCarriesStartInstantForSystemCountdown() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(9, 55),
            reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        val expected = LocalDateTime.of(2026, 10, 13, 10, 10)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, plan.startAtMillis)
    }

    @Test
    fun ongoingAtExactStartIsFull() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 10),
            reminderMinutes = 30,
        )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals(100, ongoing.progress) // 开课瞬间进度条满格，随课堂消耗
        assertEquals(95, ongoing.remainingMinutes)
    }

    @Test
    fun ongoingClassWinsOverNextCountdown() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(course(day = 2, 1, 2, name = "早课"), course(day = 2, 3, 4, name = "下一门")),
            sections(), tuesday(9, 50), reminderMinutes = 30,
        )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("早课", ongoing.course.name) // 下一门 09:40 起倒数，但上课中优先
    }

    @Test
    fun breakCountdownClampsToPreviousEnd() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(course(day = 2, 1, 2, name = "早课"), course(day = 2, 3, 4, name = "接档课")),
            sections(), tuesday(10, 0), reminderMinutes = 30,
        )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals("接档课", about.course.name)
        assertEquals(10, about.minutesUntil)
        assertEquals(66, about.progress) // 倒数从下课 09:55 起：15 分钟剩 10 分钟
        assertEquals("还有 10 分", about.chip)
    }

    @Test
    fun earlyMorningIsPlainUpcoming() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(8, 0),
            reminderMinutes = 30,
        )
        val upcoming = plan as CapsulePlan.Upcoming
        assertEquals(130, upcoming.minutesUntil)
        assertEquals("下节课", upcoming.chip) // 还没到课前 30 分钟窗口
    }

    @Test
    fun countdownStartsExactlyAtReminderTime() {
        val courses = listOf(course(day = 2, 3, 4))
        val before = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(9, 39), reminderMinutes = 30,
        )
        assertTrue(before is CapsulePlan.Upcoming)
        val at = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(9, 40), reminderMinutes = 30,
        )
        val about = at as CapsulePlan.AboutToStart
        assertEquals(100, about.progress) // 09:40 恰好是开课前 30 分钟：倒数窗口满格
        assertEquals(30, about.minutesUntil)
    }

    @Test
    fun withinReminderWindowCountsProgress() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(9, 55),
            reminderMinutes = 30,
        )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals(50, about.progress) // 09:40→10:10 共 1800s，剩余 900s
        assertEquals(15, about.minutesUntil)
    }

    @Test
    fun reminderDisabledKeepsPlainUpcoming() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(9, 55),
            reminderMinutes = 0,
        )
        val upcoming = plan as CapsulePlan.Upcoming
        assertEquals(15, upcoming.minutesUntil)
    }

    @Test
    fun atExactEndFlipsToNextCountdown() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(course(day = 2, 1, 2), course(day = 2, 3, 4, name = "下一门")),
            sections(), tuesday(9, 55), reminderMinutes = 30,
        )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals("下一门", about.course.name)
        assertEquals(15, about.minutesUntil)
        assertEquals(100, about.progress) // 下课瞬间开始倒数：倒数窗口满格
    }

    @Test
    fun afterLastClassShowsTomorrowFirstCourse() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(
                course(day = 2, 3, 4),
                course(day = 3, 1, 2, name = "周三早课"),
            ),
            sections(), tuesday(12, 30), reminderMinutes = 30,
        )
        val done = plan as CapsulePlan.DayDone
        assertTrue(done.hadClassesToday)
        assertEquals("明天 08:20 · 周三早课", done.nextText)
        assertEquals("已结束", done.chip)
    }

    @Test
    fun weekMaskHidesEvenWeekCourseInOddWeek() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(course(day = 2, 3, 4, weeks = listOf(8, 10), name = "实验课")),
            sections(), tuesday(10, 30), reminderMinutes = 30,
        )
        val done = plan as CapsulePlan.DayDone
        assertEquals(false, done.hadClassesToday)
        assertEquals("无课", done.chip)
        // 下一次出现在第 8 周周二（10-20）
        assertEquals("周二 10:10 · 实验课", done.nextText)
    }

    @Test
    fun upcomingLabelsFridayWhenNoOtherDays() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 5, 5, 6, name = "周五下午课")),
            sections(), tuesday(12, 30), reminderMinutes = 30,
        )
        val done = plan as CapsulePlan.DayDone
        assertEquals("周五 14:20 · 周五下午课", done.nextText)
    }

    @Test
    fun courseWithMissingSectionTimeIsIgnored() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 13, 13)), sections(), tuesday(10, 30),
            reminderMinutes = 30,
        )
        val done = plan as CapsulePlan.DayDone
        assertEquals(false, done.hadClassesToday)
        assertNull(done.nextText)
    }

    @Test
    fun holidayOffDayShowsHolidayState() {
        val plan = buildCapsulePlan(
            timetable(),
            listOf(course(day = 2, 3, 4), course(day = 3, 1, 2, name = "周三早课")),
            sections(), tuesday(10, 30), reminderMinutes = 30,
            holidayName = "国庆节",
        )
        val holiday = plan as CapsulePlan.Holiday
        assertEquals("国庆节", holiday.name)
        assertEquals("假期", holiday.chip)
        assertEquals("明天 08:20 · 周三早课", holiday.nextText)
    }

    @Test
    fun makeupWorkdayKeepsNormalPlan() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4, name = "毛泽东思想")), sections(),
            tuesday(10, 30), reminderMinutes = 30,
            holidayName = null, // 调休补班日按正常上课日处理
        )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("毛泽东思想", ongoing.course.name)
    }

    @Test
    fun noTimetableIsNoTable() {
        val plan = buildCapsulePlan(null, emptyList(), sections(), tuesday(10, 30), reminderMinutes = 30)
        assertEquals(CapsulePlan.NoTable, plan)
        assertEquals("纸间课表", plan.chip)
    }

    @Test
    fun refreshBeatSecuresLiveStatesAndIdleStates() {
        // 上课中且切换还很远（11:45）：心跳 = now + 6min，课堂期间持续保鲜
        val ongoing = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 30),
            reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(epochOf(10, 36), nextRefreshAtMillis(ongoing, tuesday(10, 30)))

        // 即将上课：心跳最多 6 分钟后（10:01），入场后的第一次保活
        val about = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(9, 55),
            reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 1), nextRefreshAtMillis(about, tuesday(9, 55)))

        // 即将开课（距离切换 < 5min）：心跳 = 切换 + 1min，切换丢失 1 分钟内自愈
        val aboutNear = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 6),
            reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 11), nextRefreshAtMillis(aboutNear, tuesday(10, 6)))

        // 已结束：心跳 = 次日 00:02，切换（跨天）丢失 2 分钟内自愈，平时不打扰
        val done = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(12, 30),
            reminderMinutes = 30,
        ) as CapsulePlan.DayDone
        val beat = LocalDateTime.of(2026, 10, 14, 0, 2)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(beat, nextRefreshAtMillis(done, tuesday(12, 30)))

        assertNull(nextRefreshAtMillis(CapsulePlan.NoTable, tuesday(10, 30)))
    }

    @Test
    fun countdownCleanupDeadlineFollowsClassPhases() {
        val courses = listOf(course(day = 2, 3, 4))
        // 上课中：距下课 75 分钟 + 10s 宽限（系统在延时到点后撤下卡片，闹钟被吞也不停在 00:00）
        val ongoing = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(10, 30), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(
            75 * 60_000L + 10_000L,
            countdownCleanupDelayMillis(ongoing, epochOf(10, 30)),
        )

        // 即将上课：距开课 15 分钟 + 40s 宽限（给进程存活时的正常切换留出刷新窗口）
        val about = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(9, 55), reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        assertEquals(
            15 * 60_000L + 40_000L,
            countdownCleanupDelayMillis(about, epochOf(9, 55)),
        )

        // 非倒计时状态不设兜底
        val done = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(12, 30), reminderMinutes = 30,
        ) as CapsulePlan.DayDone
        assertNull(countdownCleanupDelayMillis(done, epochOf(12, 30)))
        assertNull(countdownCleanupDelayMillis(CapsulePlan.NoTable, epochOf(12, 30)))
    }

    @Test
    fun countdownCleanupNeverGoesNonPositive() {
        // 时钟边界（渲染恰好落在时刻线之后）：只保留宽限期，不允许 ≤0
        val ongoing = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(11, 44),
            reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        assertEquals(10_000L, countdownCleanupDelayMillis(ongoing, epochOf(11, 50)))
    }

    @Test
    fun aboutToStartChipShowsRemainingMinutes() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(10, 5),
            reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        assertEquals(5, plan.minutesUntil)
        assertEquals("还有 5 分", plan.chip)
    }

    @Test
    fun upcomingGetsPreTransitionWake() {
        // 08:00 时下节课 10:10，倒数窗口 09:40：心跳应在 09:25 先唤醒，
        // 好让 Coordinator 把 09:40 的切换升级成系统闹钟兜底
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(8, 0),
            reminderMinutes = 30,
        ) as CapsulePlan.Upcoming
        assertEquals(epochOf(9, 25), nextRefreshAtMillis(plan, tuesday(8, 0)))
    }

    @Test
    fun alarmClockFallbackOnlyNearTransitionAndWhenEnabled() {
        val now = epochOf(10, 0)
        // 15 分钟内 + 开关开启 + 有精确闹钟权限 → 用闹钟级兜底
        assertTrue(shouldUseAlarmClockFallback(now, now + 15 * 60_000L, alarmFallbackEnabled = true, exactAlarmsAllowed = true))
        // 开关关闭 → 不用闹钟图标
        assertEquals(false, shouldUseAlarmClockFallback(now, now + 5 * 60_000L, alarmFallbackEnabled = false, exactAlarmsAllowed = true))
        // 距离切换还早 → 普通精确闹钟
        assertEquals(false, shouldUseAlarmClockFallback(now, now + 16 * 60_000L, alarmFallbackEnabled = true, exactAlarmsAllowed = true))
        // 没有精确闹钟权限 → 无法调用 setAlarmClock
        assertEquals(false, shouldUseAlarmClockFallback(now, now + 5 * 60_000L, alarmFallbackEnabled = true, exactAlarmsAllowed = false))
        // 时刻已过 → 不兜底
        assertEquals(false, shouldUseAlarmClockFallback(now, now - 1L, alarmFallbackEnabled = true, exactAlarmsAllowed = true))
    }

    private fun epochOf(h: Int, m: Int) =
        LocalDateTime.of(2026, 10, 13, h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun upcomingKnowsWhenCountdownWindowOpens() {
        // 课前 30 分钟提醒：09:40 是进入倒数窗口的时刻，胶囊切换闹钟按它登记
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(8, 0),
            reminderMinutes = 30,
        ) as CapsulePlan.Upcoming
        assertEquals(epochOf(9, 40), plan.aboutToStartAtMillis)
        assertEquals(epochOf(9, 40), nextTransitionAtMillis(plan, tuesday(8, 0)))
    }

    @Test
    fun upcomingWithoutReminderTransitionsAtClassStart() {
        val plan = buildCapsulePlan(
            timetable(), listOf(course(day = 2, 3, 4)), sections(), tuesday(9, 55),
            reminderMinutes = 0,
        ) as CapsulePlan.Upcoming
        assertNull(plan.aboutToStartAtMillis)
        assertEquals(epochOf(10, 10), nextTransitionAtMillis(plan, tuesday(9, 55)))
    }

    @Test
    fun nextTransitionFollowsClassPhases() {
        val courses = listOf(course(day = 2, 3, 4))
        val ongoing = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(10, 30), reminderMinutes = 30,
        ) as CapsulePlan.Ongoing
        // 10:10–11:45 的课，主提醒节点在剩余 30 分钟处（11:15）
        assertEquals(epochOf(11, 15), nextTransitionAtMillis(ongoing, tuesday(10, 30)))

        val about = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(9, 55), reminderMinutes = 30,
        ) as CapsulePlan.AboutToStart
        assertEquals(epochOf(10, 10), nextTransitionAtMillis(about, tuesday(9, 55)))

        val done = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(12, 30), reminderMinutes = 30,
        ) as CapsulePlan.DayDone
        val midnight = LocalDateTime.of(2026, 10, 14, 0, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(midnight, nextTransitionAtMillis(done, tuesday(12, 30)))

        val holiday = buildCapsulePlan(
            timetable(), courses, sections(), tuesday(10, 30), reminderMinutes = 30,
            holidayName = "国庆节",
        ) as CapsulePlan.Holiday
        assertEquals(midnight, nextTransitionAtMillis(holiday, tuesday(10, 30)))

        assertNull(nextTransitionAtMillis(CapsulePlan.NoTable, tuesday(10, 30)))
    }
}
