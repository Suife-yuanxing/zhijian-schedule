package com.zhijian.schedule

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.AdjacentCourseMerge
import com.zhijian.schedule.core.util.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjacentCourseMergeTest {

    private val totalWeeks = 20
    private val weeks = WeekMask.parseExpression(totalWeeks, "8-15")!!
    private val sections = listOf(
        section(1, "08:20", "09:05"),
        section(2, "09:10", "09:55"),
        section(3, "10:10", "10:55"),
        section(4, "11:00", "11:45"),
        section(5, "14:20", "15:05"),
        section(6, "15:10", "15:55"),
        section(7, "16:05", "16:50"),
        section(8, "16:55", "17:40"),
        section(9, "18:20", "19:05"),
        section(10, "19:10", "19:55"),
    )

    @Test
    fun `merges consecutive periods of the same course`() {
        val merged = AdjacentCourseMerge.drafts(
            listOf(draft(5, 6), draft(7, 8)),
            totalWeeks,
            sections,
        )

        assertEquals(1, merged.size)
        assertEquals(5, merged[0].startSection)
        assertEquals(8, merged[0].endSection)
        assertEquals("8D407", merged[0].room)
        assertEquals("张老师", merged[0].teacher)
    }

    @Test
    fun `merges adjacent records regardless of input order`() {
        val merged = AdjacentCourseMerge.drafts(
            listOf(draft(7, 8), draft(5, 6)),
            totalWeeks,
            sections,
        )

        assertEquals(1, merged.size)
        assertEquals(5, merged[0].startSection)
        assertEquals(8, merged[0].endSection)
    }

    @Test
    fun `does not merge across the lunch break`() {
        val merged = AdjacentCourseMerge.drafts(
            listOf(draft(3, 4), draft(5, 6)),
            totalWeeks,
            sections,
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `does not merge different rooms or week ranges`() {
        val differentRoom = AdjacentCourseMerge.drafts(
            listOf(draft(5, 6, room = "8D407"), draft(7, 8, room = "8D408")),
            totalWeeks,
            sections,
        )
        val differentWeeks = AdjacentCourseMerge.drafts(
            listOf(draft(5, 6), draft(7, 8, mask = WeekMask.parseExpression(totalWeeks, "1-16")!!)),
            totalWeeks,
            sections,
        )

        assertEquals(2, differentRoom.size)
        assertEquals(2, differentWeeks.size)
    }

    @Test
    fun `fills blank room and teacher while merging`() {
        val merged = AdjacentCourseMerge.drafts(
            listOf(
                draft(5, 6, room = "", teacher = ""),
                draft(7, 8, room = "8D407", teacher = "张老师"),
            ),
            totalWeeks,
            sections,
        )

        assertEquals(1, merged.size)
        assertEquals("8D407", merged[0].room)
        assertEquals("张老师", merged[0].teacher)
    }

    @Test
    fun `only merges exactly adjacent sections`() {
        val merged = AdjacentCourseMerge.drafts(
            listOf(draft(1, 2), draft(4, 5)),
            totalWeeks,
            sections,
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `entities keep the first record and manual flag`() {
        val merged = AdjacentCourseMerge.entities(
            listOf(
                entity(id = 1, start = 5, end = 6),
                entity(id = 2, start = 7, end = 8, manuallyEdited = true),
            ),
            totalWeeks,
            sections,
        )

        assertEquals(1, merged.size)
        assertEquals(1L, merged[0].id)
        assertEquals(5, merged[0].startSection)
        assertEquals(8, merged[0].endSection)
        assertTrue(merged[0].manuallyEdited)
    }

    @Test
    fun `entities do not merge across a long break`() {
        val merged = AdjacentCourseMerge.entities(
            listOf(entity(id = 1, start = 8, end = 8), entity(id = 2, start = 9, end = 9)),
            totalWeeks,
            sections,
        )

        assertEquals(2, merged.size)
        assertFalse(merged.any { it.startSection == 8 && it.endSection == 9 })
    }

    private fun draft(
        start: Int,
        end: Int,
        room: String = "8D407",
        teacher: String = "张老师",
        mask: String = weeks,
    ) = CourseDraft(
        name = "包装装潢设计",
        teacher = teacher,
        room = room,
        dayOfWeek = 1,
        startSection = start,
        endSection = end,
        weekMask = mask,
    )

    private fun entity(
        id: Long,
        start: Int,
        end: Int,
        manuallyEdited: Boolean = false,
    ) = CourseEntity(
        id = id,
        tableId = 1,
        name = "包装装潢设计",
        teacher = "张老师",
        room = "8D407",
        dayOfWeek = 1,
        startSection = start,
        endSection = end,
        weekMask = weeks,
        color = 0xFFDFF3E4.toInt(),
        manuallyEdited = manuallyEdited,
    )

    private fun section(no: Int, start: String, end: String) = SectionTimeEntity(
        modeId = 1,
        sectionNo = no,
        periodNo = (no + 1) / 2,
        session = if (start < "12:00") "AM" else "PM",
        startTime = start,
        endTime = end,
    )
}
