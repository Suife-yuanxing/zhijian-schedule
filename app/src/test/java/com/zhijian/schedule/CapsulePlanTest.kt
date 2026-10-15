package com.zhijian.schedule

import com.zhijian.schedule.capsule.CapsulePlan
import com.zhijian.schedule.capsule.buildCapsulePlan
import com.zhijian.schedule.capsule.nextTransitionAtMillis
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
        color: Int = 0xFF4285F4.toInt(),
    ) = CourseEntity(
        id = 0, tableId = 1, name = name, room = room,
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
        assertEquals("即将上课", about.chip)
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
        assertEquals(epochOf(11, 45), nextTransitionAtMillis(ongoing, tuesday(10, 30)))

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
