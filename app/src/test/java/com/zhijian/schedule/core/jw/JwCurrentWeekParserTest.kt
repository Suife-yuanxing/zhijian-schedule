package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从课表页 HTML 读「当前第 N 周」。
 *
 * 这个数字一旦读错，反推出的开学日期会让整学期周次整体平移，而界面上看不出异常，
 * 所以测试重点全在「宁可不取也不能取错」：只认明确写着的位置，不搞启发式。
 */
class JwCurrentWeekParserTest {
    @Test
    fun reads_selected_option_of_week_select() {
        val html =
            """
            <html><body>
              <select id="zc">
                <option value="9">第9周</option>
                <option value="10" selected>第10周</option>
                <option value="11">第11周</option>
              </select>
            </body></html>
            """.trimIndent()
        assertEquals(10, JwCurrentWeekParser.parse(html))
    }

    @Test
    fun reads_current_week_from_plain_text() {
        assertEquals(10, JwCurrentWeekParser.parse("<div>当前第 10 周</div>"))
        assertEquals(3, JwCurrentWeekParser.parse("<div>本周为第3周</div>"))
    }

    /**
     * 最容易犯的错：把课程周次当成当前周。页面里满是「1-16周」这种文本，
     * 若按「取最大的周次数字」猜，就会读出 16——整学期直接偏 6 周。
     */
    @Test
    fun course_week_expressions_are_not_mistaken_for_current_week() {
        val html =
            """
            <html><body>
              <table><tr><td>高等数学<br/>1-16周<br/>[01-02节]</td></tr></table>
            </body></html>
            """.trimIndent()
        assertNull(JwCurrentWeekParser.parse(html))
    }

    @Test
    fun out_of_range_select_value_is_ignored() {
        val html = """<select id="zc"><option value="99" selected>99</option></select>"""
        assertNull(JwCurrentWeekParser.parse(html))
    }

    @Test
    fun blank_or_unrelated_page_yields_null() {
        assertNull(JwCurrentWeekParser.parse(""))
        assertNull(JwCurrentWeekParser.parse("<html><body>没有周次信息</body></html>"))
    }
}
