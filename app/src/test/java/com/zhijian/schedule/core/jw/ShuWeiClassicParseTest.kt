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

    /**
     * 树维真实形态：课程名 / 教师 / 教室 / 周次是**相邻的同级 div**，中间没有 <br>。
     *
     * 只按 <br> 切行时整格会被读成一行「高等数学张三A1011-16周」，被 isWeekLine 判成
     * 周次行、isCourseName 判否，整格课程全部丢失（修前实测 count=0）。
     * 同理 eams5 的 print-data / datum 内嵌 HTML 也是这个结构。
     */
    @Test
    fun `adjacent divs become separate lines`() {
        val divOnly =
            """
            <table id="manualArrangeCourseTable">
              <thead><tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr></thead>
              <tbody><tr><td>1</td>
                <td>
                  <div class="course-name">高等数学</div>
                  <div class="course-teacher">张三</div>
                  <div class="course-room">A101</div>
                  <div class="course-week">1-16周</div>
                </td>
                <td></td><td></td></tr></tbody>
            </table>
            """.trimIndent()
        val drafts = HtmlTableScheduleParser.parse(divOnly, 20)
        assertEquals(1, drafts.size)
        val course = drafts.first()
        assertEquals("高等数学", course.name)
        assertEquals("张三", course.teacher)
        assertEquals("A101", course.room)
        assertEquals(16, course.weekMask.count { it == '1' })
    }

    /** 一格多门课：树维用 hr 分隔，切块后各自成课，不能被并成一条 */
    @Test
    fun `hr separated courses in one cell stay separate`() {
        val twoCourses =
            """
            <table id="manualArrangeCourseTable">
              <thead><tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr></thead>
              <tbody><tr><td>1</td>
                <td>
                  <div class="course-name">高等数学</div>
                  <div class="course-teacher">张三</div>
                  <div class="course-week">1-8周</div>
                  <hr/>
                  <div class="course-name">大学英语</div>
                  <div class="course-teacher">李四</div>
                  <div class="course-week">9-16周</div>
                </td>
                <td></td><td></td></tr></tbody>
            </table>
            """.trimIndent()
        val drafts = HtmlTableScheduleParser.parse(twoCourses, 20)
        assertEquals(2, drafts.size)
        // 顺序即单元格里的出现次序；不 sorted——中文按码位排会把「大」排到「高」前面
        assertEquals(listOf("高等数学", "大学英语"), drafts.map { it.name })
        assertEquals(8, drafts[0].weekMask.count { it == '1' })
        assertEquals(8, drafts[1].weekMask.count { it == '1' })
    }
}
