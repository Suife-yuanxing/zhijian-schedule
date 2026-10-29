package com.zhijian.schedule.core.capsule

import com.zhijian.schedule.core.capsule.CapsulePlan
import com.zhijian.schedule.core.capsule.buildCapsulePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 胶囊「当前处于哪个状态」的断言：上课中 / 即将上课 / 已结束 / 假期 / 无课表，
 * 以及考试伪课程、提醒节点、周次掩码对状态选择的影响。
 *
 * 时间轴相关的断言（刷新心跳、切换闹钟、倒计时清理）在 [CapsuleScheduleTest]。
 */
class CapsulePlanTest : CapsulePlanFixtures() {
    @Test
    fun examDayMapsToPseudoCourseAndWinsSameSlot() {
        // 考试 14:00 开考（周二），同时排了一节 14:20 的课：开考后 10 分钟应显示考试态
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 5, 6, name = "下午课")),
                sections(),
                tuesday(14, 10),
                reminderMinutes = 30,
                exams = listOf(exam(at = tuesday(14, 0))),
            )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("高等数学 · 考试", ongoing.course.name)
        assertEquals("教305 · 12 号座", ongoing.course.room)
    }

    @Test
    fun examBeforeStartEntersAboutToStartWindow() {
        val plan =
            buildCapsulePlan(
                timetable(),
                emptyList(),
                sections(),
                tuesday(13, 50),
                reminderMinutes = 30,
                exams = listOf(exam(at = tuesday(14, 0))),
            )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals("高等数学 · 考试", about.course.name)
        assertEquals(10, about.minutesUntil)
    }

    @Test
    fun examDayDoneNextTextMentionsExam() {
        val plan =
            buildCapsulePlan(
                timetable(),
                emptyList(),
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
                exams = listOf(exam(at = LocalDateTime.of(2026, 10, 14, 14, 0))),
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals("明天 14:00 · 高等数学考试", done.nextText)
    }

    @Test
    fun examNotTodayStillShowsInNextText() {
        val plan =
            buildCapsulePlan(
                timetable(),
                emptyList(),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
                exams = listOf(exam(at = LocalDateTime.of(2026, 10, 15, 14, 0))),
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals(false, done.hadClassesToday)
        assertEquals("周四 14:00 · 高等数学考试", done.nextText)
    }

    @Test
    fun examEarlierThanCourseWinsNextText() {
        // 明天既有 08:20 的课也有 14:00 的考试 → nextText 取较早的课
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 3, 1, 2, name = "周三早课")),
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
                exams = listOf(exam(at = LocalDateTime.of(2026, 10, 14, 14, 0))),
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals("明天 08:20 · 周三早课", done.nextText)
    }

    @Test
    fun ongoingMidClassComputesProgress() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4, name = "毛泽东思想", room = "8B101")),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("毛泽东思想", ongoing.course.name)
        assertEquals(78, ongoing.progress) // 10:10→11:45 共 5700s，剩余 4500s
        assertEquals(75, ongoing.remainingMinutes)
        assertEquals("剩 75 分", ongoing.chip)
        assertEquals("第3-4节", ongoing.course.sectionText)
        assertEquals("张老师", ongoing.course.teacher)
    }

    @Test
    fun ongoingLastMinuteShowsFinalChip() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                LocalDateTime.of(2026, 10, 13, 11, 44, 30),
                reminderMinutes = 30,
            )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals(0, ongoing.remainingMinutes) // 不足 1 分钟按 0 计
        assertEquals("即将下课", ongoing.chip)
    }

    @Test
    fun ongoingProgressDepletesOverTime() {
        val early =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 20),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        val late =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(11, 20),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertTrue(early.progress > late.progress) // 倒着消耗：越上课剩余占比越小
        assertEquals(89, early.progress) // 剩余 5100s / 5700s
        assertEquals(26, late.progress) // 剩余 1500s / 5700s
    }

    @Test
    fun ongoingCarriesEndInstantForSystemCountdown() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        val expected =
            LocalDateTime
                .of(2026, 10, 13, 11, 45)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
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
        val atNode =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 15),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(30L, atNode.reminderMinutes)
        assertEquals("距下课30分", atNode.chip)
        assertEquals(epochOf(11, 17), atNode.reminderUntilMillis)
        assertEquals(epochOf(11, 40), atNode.nextReminderAtMillis)

        // 2 分钟窗口内打开 App，仍应恢复到提醒态
        val withinWindow =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 16),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(30L, withinWindow.reminderMinutes)
    }

    @Test
    fun ongoingReminderCopyMentionsRemainingMinutes() {
        val courses = listOf(course(day = 2, 3, 4, name = "毛泽东思想", room = "8B101"))
        val reminder =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 15),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        val (reminderTitle, reminderText) = CapsuleNotifier.texts(reminder)
        assertEquals("距离下课仅剩 30 分钟", reminderTitle)
        assertEquals("毛泽东思想 · 8B101 · 张老师 · 11:45 下课", reminderText)

        val live =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        val (liveTitle, liveText) = CapsuleNotifier.texts(live)
        assertEquals("正在上课 · 毛泽东思想", liveTitle)
        assertEquals("8B101 · 张老师 · 11:45 下课 · 还剩 75 分钟", liveText)
    }

    @Test
    fun ongoingReminderRevertsAfterHighlightWindow() {
        val courses = listOf(course(day = 2, 3, 4))
        val after =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 17),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertNull(after.reminderMinutes)
        assertEquals("剩 28 分", after.chip)
        assertEquals(epochOf(11, 40), after.nextReminderAtMillis)
        assertEquals(epochOf(11, 40), nextTransitionAtMillis(after, tuesday(11, 17)))
    }

    @Test
    fun finalFiveMinuteNodeAlsoHighlights() {
        val courses = listOf(course(day = 2, 3, 4))
        val atNode =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 40),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(5L, atNode.reminderMinutes)
        assertEquals("距下课5分", atNode.chip)
        assertEquals(epochOf(11, 42), atNode.reminderUntilMillis)

        val after =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 42),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertNull(after.reminderMinutes)
        assertEquals("剩 3 分", after.chip)
        assertEquals(epochOf(11, 45), nextTransitionAtMillis(after, tuesday(11, 42)))
    }

    @Test
    fun nextTransitionTargetsReminderNodeAndHighlightEnd() {
        val courses = listOf(course(day = 2, 3, 4))
        val beforeNode =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(epochOf(11, 15), nextTransitionAtMillis(beforeNode, tuesday(10, 30)))

        val atNode =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(11, 15),
                reminderMinutes = 30,
            ) as CapsulePlan.Ongoing
        assertEquals(epochOf(11, 17), nextTransitionAtMillis(atNode, tuesday(11, 15)))
    }

    @Test
    fun aboutToStartCarriesStartInstantForSystemCountdown() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            ) as CapsulePlan.AboutToStart
        val expected =
            LocalDateTime
                .of(2026, 10, 13, 10, 10)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        assertEquals(expected, plan.startAtMillis)
    }

    @Test
    fun ongoingAtExactStartIsFull() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(10, 10),
                reminderMinutes = 30,
            )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals(100, ongoing.progress) // 开课瞬间进度条满格，随课堂消耗
        assertEquals(95, ongoing.remainingMinutes)
    }

    @Test
    fun ongoingClassWinsOverNextCountdown() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 1, 2, name = "早课"), course(day = 2, 3, 4, name = "下一门")),
                sections(),
                tuesday(9, 50),
                reminderMinutes = 30,
            )
        val ongoing = plan as CapsulePlan.Ongoing
        assertEquals("早课", ongoing.course.name) // 下一门 09:40 起倒数，但上课中优先
    }

    @Test
    fun breakCountdownClampsToPreviousEnd() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 1, 2, name = "早课"), course(day = 2, 3, 4, name = "接档课")),
                sections(),
                tuesday(10, 0),
                reminderMinutes = 30,
            )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals("接档课", about.course.name)
        assertEquals(10, about.minutesUntil)
        assertEquals(66, about.progress) // 倒数从下课 09:55 起：15 分钟剩 10 分钟
        assertEquals("还有 10 分", about.chip)
    }

    @Test
    fun earlyMorningIsPlainUpcoming() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(8, 0),
                reminderMinutes = 30,
            )
        val upcoming = plan as CapsulePlan.Upcoming
        assertEquals(130, upcoming.minutesUntil)
        assertEquals("下节课", upcoming.chip) // 还没到课前 30 分钟窗口
    }

    @Test
    fun countdownStartsExactlyAtReminderTime() {
        val courses = listOf(course(day = 2, 3, 4))
        val before =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(9, 39),
                reminderMinutes = 30,
            )
        assertTrue(before is CapsulePlan.Upcoming)
        val at =
            buildCapsulePlan(
                timetable(),
                courses,
                sections(),
                tuesday(9, 40),
                reminderMinutes = 30,
            )
        val about = at as CapsulePlan.AboutToStart
        assertEquals(100, about.progress) // 09:40 恰好是开课前 30 分钟：倒数窗口满格
        assertEquals(30, about.minutesUntil)
    }

    @Test
    fun withinReminderWindowCountsProgress() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals(50, about.progress) // 09:40→10:10 共 1800s，剩余 900s
        assertEquals(15, about.minutesUntil)
    }

    @Test
    fun reminderDisabledKeepsPlainUpcoming() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4)),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 0,
            )
        val upcoming = plan as CapsulePlan.Upcoming
        assertEquals(15, upcoming.minutesUntil)
    }

    @Test
    fun atExactEndFlipsToNextCountdown() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 1, 2), course(day = 2, 3, 4, name = "下一门")),
                sections(),
                tuesday(9, 55),
                reminderMinutes = 30,
            )
        val about = plan as CapsulePlan.AboutToStart
        assertEquals("下一门", about.course.name)
        assertEquals(15, about.minutesUntil)
        assertEquals(100, about.progress) // 下课瞬间开始倒数：倒数窗口满格
    }

    @Test
    fun afterLastClassShowsTomorrowFirstCourse() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(
                    course(day = 2, 3, 4),
                    course(day = 3, 1, 2, name = "周三早课"),
                ),
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
            )
        val done = plan as CapsulePlan.DayDone
        assertTrue(done.hadClassesToday)
        assertEquals("明天 08:20 · 周三早课", done.nextText)
        assertEquals("已结束", done.chip)
    }

    @Test
    fun weekMaskHidesEvenWeekCourseInOddWeek() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4, weeks = listOf(8, 10), name = "实验课")),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals(false, done.hadClassesToday)
        assertEquals("无课", done.chip)
        // 下一次出现在第 8 周周二（10-20）
        assertEquals("周二 10:10 · 实验课", done.nextText)
    }

    @Test
    fun upcomingLabelsFridayWhenNoOtherDays() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 5, 5, 6, name = "周五下午课")),
                sections(),
                tuesday(12, 30),
                reminderMinutes = 30,
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals("周五 14:20 · 周五下午课", done.nextText)
    }

    @Test
    fun courseWithMissingSectionTimeIsIgnored() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 13, 13)),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
            )
        val done = plan as CapsulePlan.DayDone
        assertEquals(false, done.hadClassesToday)
        assertNull(done.nextText)
    }

    @Test
    fun holidayOffDayShowsHolidayState() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4), course(day = 3, 1, 2, name = "周三早课")),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
                holidayName = "国庆节",
            )
        val holiday = plan as CapsulePlan.Holiday
        assertEquals("国庆节", holiday.name)
        assertEquals("假期", holiday.chip)
        assertEquals("明天 08:20 · 周三早课", holiday.nextText)
    }

    @Test
    fun makeupWorkdayKeepsNormalPlan() {
        val plan =
            buildCapsulePlan(
                timetable(),
                listOf(course(day = 2, 3, 4, name = "毛泽东思想")),
                sections(),
                tuesday(10, 30),
                reminderMinutes = 30,
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
}
