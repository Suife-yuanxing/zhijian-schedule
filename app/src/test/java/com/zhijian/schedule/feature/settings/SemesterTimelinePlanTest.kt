package com.zhijian.schedule.feature.settings

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学期时间轴纯逻辑的边界：学期中、未开学、已结课、最后一天、空输入。
 * 关键点：「本周」用自然周序列（floorDiv），学期外不算错。
 */
class SemesterTimelinePlanTest {
    private val start = LocalDate.parse("2026-08-31")

    @Test
    fun `mid semester marks past now future`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2026-10-31"))
        assertEquals(SemesterTimelinePlan.Status.ACTIVE, model.status)
        assertEquals(9, model.currentWeek)
        assertEquals(SemesterTimelinePlan.WeekState.PAST, model.weeks[7].state)
        assertEquals(SemesterTimelinePlan.WeekState.NOW, model.weeks[8].state)
        assertEquals(SemesterTimelinePlan.WeekState.FUTURE, model.weeks[9].state)
        assertEquals(LocalDate.parse("2027-01-17"), model.endDate)
    }

    @Test
    fun `before semester starts`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2026-08-30"))
        assertEquals(SemesterTimelinePlan.Status.NOT_STARTED, model.status)
        assertNull(model.currentWeek)
        assertTrue(model.weeks.all { it.state == SemesterTimelinePlan.WeekState.FUTURE })
    }

    @Test
    fun `last day of semester is still active`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2027-01-17"))
        assertEquals(SemesterTimelinePlan.Status.ACTIVE, model.status)
        assertEquals(20, model.currentWeek)
    }

    @Test
    fun `after semester ends`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2027-01-18"))
        assertEquals(SemesterTimelinePlan.Status.FINISHED, model.status)
        assertNull(model.currentWeek)
        assertTrue(model.weeks.all { it.state == SemesterTimelinePlan.WeekState.PAST })
    }

    @Test
    fun `null or non positive inputs yield empty model`() {
        assertTrue(SemesterTimelinePlan.build(null, 20, LocalDate.parse("2026-10-31")).weeks.isEmpty())
        assertTrue(SemesterTimelinePlan.build(start, 0, LocalDate.parse("2026-10-31")).weeks.isEmpty())
    }
}
