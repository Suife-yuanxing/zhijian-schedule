package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.jw.SectionTimeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「抓完课表，作息跟着变」的决策层：
 * 哪些小节要改、改成什么、课程用到但页面没给的节次怎么补。
 */
class SectionTimePlanTest {
    private fun section(
        no: Int,
        start: String,
        end: String,
    ) = SectionTimeEntity(
        id = no.toLong(),
        modeId = 1L,
        sectionNo = no,
        periodNo = (no + 1) / 2,
        session = if (no <= 4) "AM" else "PM",
        startTime = start,
        endTime = end,
    )

    private fun course(
        start: Int,
        end: Int,
    ) = CourseEntity(
        tableId = 1L,
        name = "课",
        dayOfWeek = 1,
        startSection = start,
        endSection = end,
        weekMask = "1".repeat(20),
        color = 0,
    )

    private val current =
        listOf(
            section(1, "08:20", "09:05"),
            section(2, "09:10", "09:55"),
            section(3, "10:10", "10:55"),
            section(4, "11:00", "11:45"),
        )

    @Test
    fun `incoming times win over the current timetable`() {
        val incoming =
            listOf(
                SectionTimeDraft(1, "08:00", "08:45"),
                SectionTimeDraft(2, "08:50", "09:35"),
            )
        val plan = SectionTimePlan.plan(current, incoming, listOf(course(1, 2)))!!
        assertEquals(listOf("08:00", "08:50"), plan.take(2).map { it.startTime })
        assertEquals(listOf("10:10", "11:00"), plan.drop(2).map { it.startTime })
    }

    @Test
    fun `courses needing later sections extend the plan`() {
        val incoming = listOf(SectionTimeDraft(1, "08:00", "08:45"))
        val plan = SectionTimePlan.plan(current, incoming, listOf(course(9, 10)))!!
        assertEquals(10, plan.size)
        assertTrue(plan[8].startTime > plan[7].startTime)
    }

    @Test
    fun `nothing extracted means no change at all`() {
        assertNull(SectionTimePlan.plan(current, emptyList(), listOf(course(1, 2))))
    }

    @Test
    fun `identical times still return a plan so callers can decide`() {
        val incoming = current.map { SectionTimeDraft(it.sectionNo, it.startTime, it.endTime) }
        val plan = SectionTimePlan.plan(current, incoming, emptyList())!!
        assertEquals(4, plan.size)
        assertEquals("08:20", plan[0].startTime)
    }

    @Test
    fun `plan keeps period numbers in pairs`() {
        val incoming = (1..6).map { SectionTimeDraft(it, "0" + (7 + it) + ":00", "0" + (7 + it) + ":45") }
        val plan = SectionTimePlan.plan(current, incoming, emptyList())!!
        assertEquals(listOf(1, 1, 2, 2, 3, 3), plan.map { it.periodNo })
    }
}
