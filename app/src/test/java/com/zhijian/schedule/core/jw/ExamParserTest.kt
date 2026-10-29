package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.ExamParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamParserTest {
    private val page =
        """
        <html><body><table id="dataList">
        <tr><th>序号</th><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th><th>备注</th></tr>
        <tr><td>1</td><td>高等数学</td><td>2026-11-02 14:00~15:50</td><td>教305</td><td>12</td><td>闭卷</td></tr>
        <tr><td>2</td><td>大学英语</td><td>2026-11-05 08:20-10:00</td><td>教102</td><td>3</td><td></td></tr>
        </table></body></html>
        """.trimIndent()

    @Test fun `解析课程名 考场 座位 与开考时刻`() {
        val list = ExamParser.parse(page)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("高等数学", first.name)
        assertEquals("教305", first.room)
        assertEquals("12", first.seat)
        assertEquals("闭卷", first.note)
        assertEquals(2026, first.examTime.year)
        assertEquals(11, first.examTime.monthValue)
        assertEquals(2, first.examTime.dayOfMonth)
        assertEquals(14, first.examTime.hour)
        assertEquals(0, first.examTime.minute)
    }

    @Test fun `无法定位时间的行被跳过`() {
        val html = page.replace("<td>2026-11-05 08:20-10:00</td>", "<td>待定</td>")
        val list = ExamParser.parse(html)
        assertEquals(1, list.size)
        assertEquals("高等数学", list[0].name)
    }

    @Test fun `空表与登录页返回空列表`() {
        assertEquals(0, ExamParser.parse("<html><body></body></html>").size)
        assertEquals(0, ExamParser.parse("""{"msgContent":"请登录"}""").size)
    }
}
