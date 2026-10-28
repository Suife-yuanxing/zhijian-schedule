package com.zhijian.schedule.feature.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图课表导入必须把图上的作息一起带出来，
 * 否则识别完课表，设置页还停在写死的 08:20-21:35。
 */
class OcrSectionTimesTest {
    /** 图片 1 那种界面：时段头 + 每个大节的起止时间 + 星期表头 + 课程格 */
    private fun line(
        text: String,
        left: Float,
        top: Float,
        height: Float = 20f,
    ) = OcrLine(text, left, top, left + 80f, top + height)

    private val page =
        listOf(
            line("上午", 20f, 300f),
            line("08:20 - 11:45", 400f, 300f),
            line("08:20-09:55", 20f, 340f),
            line("10:10-11:45", 20f, 400f),
            line("下午", 20f, 520f),
            line("14:20 - 17:40", 400f, 520f),
            line("14:20-15:55", 20f, 560f),
            line("16:05-17:40", 20f, 620f),
            line("星期一", 120f, 340f),
            line("星期二", 200f, 340f),
            line("星期三", 280f, 340f),
            line("高等数学", 200f, 350f),
        )

    @Test
    fun `ocr parse carries the section times seen in the screenshot`() {
        val result = OcrTimetableParser.parse(listOf(page), totalWeeks = 20, totalSections = 12)
        assertEquals(8, result.sectionTimes.size)
        assertEquals("08:20", result.sectionTimes[0].startTime)
        assertEquals("11:45", result.sectionTimes[3].endTime)
        assertEquals("14:20", result.sectionTimes[4].startTime)
    }

    @Test
    fun `screenshot without any time text yields no section times`() {
        val result =
            OcrTimetableParser.parse(
                listOf(listOf(line("星期一", 100f, 100f), line("高等数学", 100f, 140f))),
                totalWeeks = 20,
                totalSections = 12,
            )
        assertTrue(result.sectionTimes.isEmpty())
    }
}
