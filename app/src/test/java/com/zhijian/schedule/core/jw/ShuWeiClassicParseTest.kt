package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShuWeiClassicParseTest {
    /**
     * 树维 classic 课表：行首是纯数字节次，单元格用 div 分层
     * （course-name / course-teacher / course-room），rowspan 覆盖连续节次。
     */
    private val html =
        """
        <html><body>
        <table class="courseTable" id="manualArrangeCourseTable">
          <thead>
            <tr><th>节次</th><th>时间</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>1</td><td>08:00-08:45</td>
              <td rowspan="2">
                <div class="course-name">高等数学</div>
                <div class="course-teacher">张三</div>
                <div class="course-room">A101</div>
              </td>
              <td></td><td></td>
            </tr>
            <tr><td>2</td><td>08:55-09:40</td><td></td><td></td></tr>
            <tr>
              <td>3</td><td>10:00-10:45</td>
              <td></td>
              <td>
                <div class="course-name">大学英语</div>
                <div class="course-teacher">李四</div>
                <div class="course-room">外语楼203</div>
              </td>
              <td></td>
            </tr>
          </tbody>
        </table>
        </body></html>
        """.trimIndent()

    @Test
    fun `parse shuwei classic table with numeric row labels`() {
        val drafts = HtmlTableScheduleParser.parse(html, 20)
        assertEquals(2, drafts.size)

        val math = drafts.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("张三", math.teacher)
        assertEquals("A101", math.room)

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(3, english.endSection)
        assertEquals("外语楼203", english.room)
    }

    @Test
    fun `coursetable path is recognized as shuwei classic`() {
        assertEquals(
            "shuwei_classic",
            JwAdapterRegistry
                .forUrl("https://jw.example.edu.cn/eams/coursetable/courseTableForStd!courseTable.action?ignoreHead=1")
                ?.id,
        )
    }

    @Test
    fun `eams root is recognized as shuwei classic`() {
        assertEquals(
            "shuwei_classic",
            JwAdapterRegistry.forUrl("https://jwxt.example.edu.cn/eams/login.action")?.id,
        )
    }

    /** eams5 是三段式 JSON 的另一套接口，不能按 classic 解析 */
    @Test
    fun `eams5 student path is not shuwei classic`() {
        val adapter =
            JwAdapterRegistry.forUrl("https://jw.example.edu.cn/eams5-student/for-std/course-table")
        assertNotEquals("shuwei_classic", adapter?.id)
    }

    @Test
    fun `shuwei normalize keeps eams base`() {
        assertEquals(
            "https://jw.example.edu.cn/eams/",
            ShuWeiClassicAdapter.normalizeBase(
                "https://jw.example.edu.cn/eams/coursetable/courseTableForStd!courseTable.action?ignoreHead=1",
            ),
        )
    }

    @Test
    fun `host without eams falls back to eams base`() {
        assertEquals(
            "https://jw.example.edu.cn/eams/",
            ShuWeiClassicAdapter.normalizeBase("https://jw.example.edu.cn/"),
        )
    }
}
