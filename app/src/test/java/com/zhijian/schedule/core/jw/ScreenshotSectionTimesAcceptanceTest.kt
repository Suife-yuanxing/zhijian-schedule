package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 端到端验收：截图课表上的时间轴 → 设置页的作息列表。
 *
 * 数据取自真机截图（周三 10/28 那张周视图 + 对应设置页）：
 * 截图左侧写的是「上午 08:20 - 11:45」这类时段头和大节时间「08:20-09:55」，
 * 设置页要出现的是拆到小节的 12 行。两边必须是同一套数字——作息不写死在代码里。
 */
class ScreenshotSectionTimesAcceptanceTest {
    /** 周视图截图上能 OCR 到的全部时间文字（含被浮动按钮挡住的晚间时段，按已知值补全） */
    private val screenshotLines =
        listOf(
            "上午",
            "08:20 - 11:45",
            "08:20-09:55",
            "10:10-11:45",
            "下午",
            "14:20 - 17:40",
            "14:20 - 15:55",
            "16:05-17:40",
            "晚上",
            "18:20 - 21:35",
            "18:20-19:55",
            "20:00-21:35",
        )

    /** 设置页截图上的 12 行 */
    private val expected =
        listOf(
            "08:20" to "09:05",
            "09:10" to "09:55",
            "10:10" to "10:55",
            "11:00" to "11:45",
            "14:20" to "15:05",
            "15:10" to "15:55",
            "16:05" to "16:50",
            "16:55" to "17:40",
            "18:20" to "19:05",
            "19:10" to "19:55",
            "20:00" to "20:45",
            "20:50" to "21:35",
        )

    @Test
    fun `screenshot timeline reproduces the settings page section list`() {
        val out = SectionTimes.fromLines(screenshotLines)
        assertEquals(expected.size, out.size)
        out.forEachIndexed { i, section ->
            assertEquals("第 " + (i + 1) + " 节开始", expected[i].first, section.startTime)
            assertEquals("第 " + (i + 1) + " 节结束", expected[i].second, section.endTime)
            assertEquals(i + 1, section.sectionNo)
        }
    }

    /**
     * 新装用户的初始作息必须和抓取结果同一套数字：
     * 初始值也是从「六个大节区间」推出来的，不是另抄一份常量表。
     */
    @Test
    fun `built-in defaults match the settings page list exactly`() {
        val out = SectionTimes.defaults()
        assertEquals(expected.size, out.size)
        out.forEachIndexed { i, section ->
            assertEquals(expected[i].first, section.startTime)
            assertEquals(expected[i].second, section.endTime)
            assertEquals(i + 1, section.sectionNo)
        }
    }

    @Test
    fun `only the evening band is visible the same numbers still come out for that band`() {
        val eveningOnly = listOf("晚上 18:20 - 21:35", "18:20-19:55", "20:00-21:35")
        val out = SectionTimes.fromLines(eveningOnly)
        assertEquals(4, out.size)
        assertEquals("18:20", out[0].startTime)
        assertEquals("21:35", out[3].endTime)
    }
}
