package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.feature.importer.parsers.IcsParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    private val semesterStart = LocalDate.of(2026, 8, 31)

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
                modeId = 1,
                sectionNo = no,
                periodNo = (no + 1) / 2,
                session = if (no <= 4) "AM" else "PM",
                startTime = s,
                endTime = e,
            )
        }
    }

    @Test
    fun parsesWeeklyUntilRule() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:包装材料与检测
            LOCATION:8A206
            DESCRIPTION:教师：张三
            DTSTART;TZID=Asia/Shanghai:20260901T082000
            DTEND;TZID=Asia/Shanghai:20260901T095000
            RRULE:FREQ=WEEKLY;UNTIL=20261030T235959Z;BYDAY=TU
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = IcsParser.parse(ics, totalWeeks = 20, semesterStart = semesterStart, sections = sections())

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("包装材料与检测", d.name)
        assertEquals(2, d.dayOfWeek)
        assertEquals(1, d.startSection)
        assertEquals(2, d.endSection)
        assertEquals("8A206", d.room)
        assertEquals("张三", d.teacher)
        assertEquals(9, WeekMask.count(d.weekMask))
        assertTrue(WeekMask.contains(d.weekMask, 1))
        assertTrue(WeekMask.contains(d.weekMask, 9))
        assertTrue(!WeekMask.contains(d.weekMask, 10))
    }

    @Test
    fun unfoldsSummaryAndHonoursCount() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:数据结构\r\n 与算法\r\n" +
            "DTSTART;TZID=\"Asia/Shanghai\":20260903T101000\r\nDTEND:20260903T114500\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=3;BYDAY=TH\r\nEND:VEVENT\r\nEND:VCALENDAR"

        val result = IcsParser.parse(ics, totalWeeks = 20, semesterStart = semesterStart, sections = sections())

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("数据结构与算法", d.name)
        assertEquals(4, d.dayOfWeek)
        assertEquals(3, d.startSection)
        assertEquals(4, d.endSection)
        assertEquals(listOf(1, 2, 3), WeekMask.weeks(d.weekMask))
    }

    @Test
    fun mergesEventsAndSkipsInvalid() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:线性代数
            DESCRIPTION:教师：王五\n教室：B区101
            DTSTART:20260907T082000
            RRULE:FREQ=WEEKLY;COUNT=2;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:线性代数
            DTSTART:20261005T082000
            RRULE:FREQ=WEEKLY;COUNT=2;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:全天活动
            DTSTART;VALUE=DATE:20260901
            END:VEVENT
            BEGIN:VEVENT
            DTSTART:20260908T082000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = IcsParser.parse(ics, totalWeeks = 20, semesterStart = semesterStart, sections = sections())

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("线性代数", d.name)
        assertEquals(1, d.dayOfWeek)
        assertEquals(listOf(2, 3, 6, 7), WeekMask.weeks(d.weekMask))
        assertEquals("B区101", d.room)
        assertEquals("王五", d.teacher)
        assertEquals(2, result.skipped)
        assertTrue(result.warnings.any { it.contains("跳过") })
    }

    @Test
    fun outOfRangeEventsAreDropped() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:20260115T080000
            RRULE:FREQ=WEEKLY;COUNT=1;BYDAY=TH
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = IcsParser.parse(ics, totalWeeks = 20, semesterStart = semesterStart, sections = sections())

        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun requiresValidSemesterStart() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:20260903T080000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = IcsParser.parse(ics, totalWeeks = 20, semesterStart = null, sections = sections())

        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.first().contains("开学日期"))
    }
}
