package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图课表（图片 1 那种界面）里的时间轴文字 → 作息。
 *
 * 截图上有两类时间：时段头「上午 08:20 - 11:45」和大节时间「08:20-09:55」。
 * 前者整体套住后者，所以要按「被包含者胜出」去重，再按出现顺序编大节号。
 */
class SectionTimesLinesTest {
    @Test
    fun `period ranges beat the enclosing band header`() {
        val lines =
            listOf(
                "上午 08:20 - 11:45",
                "08:20-09:55",
                "10:10-11:45",
                "下午 14:20 - 17:40",
                "14:20 - 15:55",
                "16:05-17:40",
            )
        val out = SectionTimes.fromLines(lines)
        assertEquals(8, out.size)
        assertEquals("08:20", out[0].startTime)
        assertEquals("09:55", out[1].endTime)
        assertEquals("10:10", out[2].startTime)
        assertEquals("14:20", out[4].startTime)
        assertEquals("17:40", out[7].endTime)
    }

    @Test
    fun `band header alone still yields a usable timetable`() {
        val out = SectionTimes.fromLines(listOf("上午 08:20 - 11:45"))
        // 一段 205 分钟摊成 4 小节：45 + 课间均分
        assertEquals(4, out.size)
        assertEquals("08:20", out[0].startTime)
        assertEquals("11:45", out[3].endTime)
    }

    @Test
    fun `lines without any time are ignored`() {
        assertTrue(SectionTimes.fromLines(listOf("高等数学", "张三", "1-16周")).isEmpty())
    }

    @Test
    fun `full-width colons and en dashes are accepted`() {
        val out = SectionTimes.fromLines(listOf("08：20 – 09：55"))
        assertEquals("08:20", out[0].startTime)
        assertEquals("09:05", out[0].endTime)
        assertEquals("09:10", out[1].startTime)
    }
}
