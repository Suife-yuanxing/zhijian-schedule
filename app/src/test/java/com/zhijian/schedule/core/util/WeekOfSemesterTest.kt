package com.zhijian.schedule.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 周次公式：开学日 + 总周数 + 某一天 → 第几周。
 *
 * 这组断言之前是空白——「第 XX 周」是顶栏、今日页、我的页、小组件、流体云共用的唯一口径，
 * 却没有一个用例守它。边界（开学前一天 / 开学当天 / 结课当天 / 结课后一天）必须钉住。
 */
class WeekOfSemesterTest {
    private val start: LocalDate = LocalDate.of(2026, 8, 31) // 周一

    @Test
    fun firstDayIsWeekOne() {
        assertEquals(1, TimeUtils.weekOfSemester(start, 18, start))
        assertEquals(1, TimeUtils.weekOfSemester(start, 18, start.plusDays(6)))
    }

    @Test
    fun weekRollsOverOnMonday() {
        assertEquals(1, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 9, 6))) // 周日
        assertEquals(2, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 9, 7))) // 周一
        assertEquals(10, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 11, 2)))
        assertEquals(10, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 11, 8)))
        assertEquals(11, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun lastWeekStaysInsideRange() {
        assertEquals(18, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 12, 28)))
        assertEquals(18, TimeUtils.weekOfSemester(start, 18, LocalDate.of(2027, 1, 3)))
    }

    /** 开学前一天：返回 null 而不是 0 或负数，调用方据此显示「未开学」 */
    @Test
    fun beforeSemesterIsNull() {
        assertNull(TimeUtils.weekOfSemester(start, 18, start.minusDays(1)))
        assertNull(TimeUtils.weekOfSemester(start, 18, LocalDate.of(2026, 8, 24)))
    }

    /** 结课后一天：返回 null 而不是 19，调用方据此显示「学期已结束」 */
    @Test
    fun afterSemesterIsNull() {
        assertNull(TimeUtils.weekOfSemester(start, 18, LocalDate.of(2027, 1, 4)))
    }

    /**
     * 总周数被改小时，同一天可能从「第 19 周」变成「学期外」。
     * 这就是 WeekAnchorPolicy 要把页码夹回范围内的原因。
     */
    @Test
    fun shrinkingTotalWeeksCanPushDayOutside() {
        val day = LocalDate.of(2027, 1, 4)
        assertEquals(19, TimeUtils.weekOfSemester(start, 20, day))
        assertNull(TimeUtils.weekOfSemester(start, 18, day))
    }

    @Test
    fun zeroTotalWeeksIsAlwaysOutside() {
        assertNull(TimeUtils.weekOfSemester(start, 0, start))
    }
}

/**
 * 跨零点驱动的等待时长。
 *
 * [WeekPlan.todayTicks] 本身要等真实时间，测不了；但「下一次该睡多久」是纯函数，
 * 这里把几个关键刻度钉住：跨零点前一刻不能睡 0（否则空转），也不能睡过头到第二天之后。
 */
class WeekPlanTickTest {
    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    private fun at(
        date: String,
        time: String,
    ) = java.time.LocalDateTime
        .parse("$date" + "T" + time)
        .atZone(zone)

    @Test
    fun sleepsUntilJustAfterMidnight() {
        // 23:59:00 → 还剩 60 秒，加上 1 秒余量
        assertEquals(61_000L, WeekPlan.waitUntilNextDay(at("2026-11-02", "23:59:00")))
    }

    @Test
    fun midnightItselfWaitsAFullDay() {
        // 00:00:00 刚过：下一个午夜在 24 小时后，不能算成 0
        assertEquals(86_401_000L, WeekPlan.waitUntilNextDay(at("2026-11-03", "00:00:00")))
    }

    @Test
    fun neverReturnsZeroOrNegative() {
        // 00:00:00.500 这种「刚跨完零点」的时刻，余量本身要兜住
        val justAfter = at("2026-11-03", "00:00:00").plusNanos(500_000_000L)
        assertTrue(WeekPlan.waitUntilNextDay(justAfter) > 0L)
    }

    @Test
    fun daytimeWaitsLessThanADay() {
        val noon = WeekPlan.waitUntilNextDay(at("2026-11-02", "12:00:00"))
        assertEquals(43_201_000L, noon)
    }
}
