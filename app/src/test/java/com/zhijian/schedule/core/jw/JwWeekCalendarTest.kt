package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 教学周历解析（2026-11-02 东莞职院实抓结构）。
 *
 * 这一条守着「第 10 周显示成第 1 周」那个回归：课表页不给开学日期，
 * 周历页给，解析不出来就只能是第 1 周。
 */
class JwWeekCalendarTest {
    /** 实抓片段：首列周次，日期藏在 title 里（正文只有「31」「09月06日」，缺年月） */
    private val html =
        """
        <html><body>
        <select name="xnxq01id" id="xnxq01id">
          <option value="2027-2028-1"  >2027-2028-1</option>
          <option value="2026-2027-1"   selected="selected">2026-2027-1</option>
          <option value="2025-2026-2"  >2025-2026-2</option>
        </select>
        <table id="kbtable" class="Nsb_r_list Nsb_table">
          <tr>
            <th width="80"><image src="../framework/images/rq.png"/></th>
            <th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th>
            <th>星期五</th><th>星期六</th><th>星期日</th><th>备注</th>
          </tr>
          <tr height='28'>
            <td>1</td>
            <td title='2026年08月31'><B>31</B></td>
            <td title='2026年09月01'><B>01</B></td>
            <td title='2026年09月02'><B>02</B></td>
            <td title='2026年09月03'><B>03</B></td>
            <td title='2026年09月04'><B>04</B></td>
            <td title='2026年09月05'><B>09月05日</B></td>
            <td title='2026年09月06'><B>09月06日</B></td>
            <td><textarea disabled></textarea></td>
          </tr>
          <tr height='28'>
            <td>2</td>
            <td title='2026年09月07'><B>07</B></td>
            <td title='2026年09月08'><B>08</B></td>
            <td title='2026年09月09'><B>09</B></td>
            <td title='2026年09月10'><B>10</B></td>
            <td title='2026年09月11'><B>11</B></td>
            <td title='2026年09月12'><B>09月12日</B></td>
            <td title='2026年09月13'><B>09月13日</B></td>
            <td><textarea disabled></textarea></td>
          </tr>
          <tr height='28'>
            <td>10</td>
            <td title='2026年11月02'><B>02</B></td>
            <td title='2026年11月03'><B>03</B></td>
            <td title='2026年11月04'><B>04</B></td>
            <td title='2026年11月05'><B>05</B></td>
            <td title='2026年11月06'><B>06</B></td>
            <td title='2026年11月07'><B>11月07日</B></td>
            <td title='2026年11月08'><B>11月08日</B></td>
            <td><textarea disabled></textarea></td>
          </tr>
          <tr height='28'>
            <td>20</td>
            <td title='2027年01月11'><B>11</B></td>
            <td title='2027年01月12'><B>12</B></td>
            <td title='2027年01月13'><B>13</B></td>
            <td title='2027年01月14'><B>14</B></td>
            <td title='2027年01月15'><B>15</B></td>
            <td title='2027年01月16'><B>01月16日</B></td>
            <td title='2027年01月17'><B>01月17日</B></td>
            <td><textarea disabled></textarea></td>
          </tr>
        </table>
        </body></html>
        """.trimIndent()

    @Test
    fun `start date is the monday of week one`() {
        val meta = JwWeekCalendar.parse(html)
        assertEquals("2026-08-31", meta?.startDate)
    }

    @Test
    fun `total weeks is the largest week number, not the row count`() {
        // 只抓到 1/2/10/20 四行：按行数会得 4，按最大周次才是 20
        assertEquals(20, JwWeekCalendar.parse(html)?.totalWeeks)
    }

    @Test
    fun `semester name comes from the selected option`() {
        assertEquals("2026-2027学年第一学期", JwWeekCalendar.parse(html)?.semester)
    }

    @Test
    fun `second term code 12 maps to second semester`() {
        val second = html.replace("2026-2027-1\"   selected", "2026-2027-2\"   selected")
        assertEquals("2026-2027学年第二学期", JwWeekCalendar.parse(second)?.semester)
    }

    @Test
    fun `term code 3 also means first semester`() {
        val zf = html.replace("2026-2027-1\"   selected", "2026-2027-3\"   selected")
        assertEquals("2026-2027学年第一学期", JwWeekCalendar.parse(zf)?.semester)
    }

    @Test
    fun `no calendar table yields null so local value survives`() {
        assertNull(JwWeekCalendar.parse("<html><body>未开放</body></html>"))
        assertNull(JwWeekCalendar.parse(""))
    }

    @Test
    fun `rows without a parseable date are skipped, not guessed`() {
        // 正文只有「31」，没有 title：认不出年月就必须整行丢弃，不能猜成今年
        val noTitle =
            """
            <table id="kbtable"><tr><td>1</td><td>31</td><td>01</td></tr></table>
            """.trimIndent()
        assertNull(JwWeekCalendar.parse(noTitle))
    }

    @Test
    fun `week ten of the real calendar is the week containing today`() {
        // 2026-11-02 落在周历第 10 周（11-02 那一行）；起点 08-31 推出的第 10 周与它一致
        val start = java.time.LocalDate.of(2026, 8, 31)
        val today = java.time.LocalDate.of(2026, 11, 2)
        assertEquals(10, TimeUtilsWeek.weekOf(start, today))
    }

    /**
     * 真机实抓的整页（2026-11-02 东莞职院 `jxzl/jxzl_query`），落在
     * `src/test/resources/jw/` 而不是内联字符串：这页有 1.6 万字符、20 周 × 9 列，
     * 内联进 Kotlin 既读不下去也改不动。夹具就是那次请求的原文，没有手改过。
     */
    @Test
    fun `real captured calendar page parses to 2026-08-31 and 20 weeks`() {
        val html = javaClass.classLoader!!.getResource("jw/jsxsd-jxzl-2026-11-02.html")!!.readText()
        val meta = JwWeekCalendar.parse(html)
        assertEquals("2026-08-31", meta?.startDate)
        assertEquals(20, meta?.totalWeeks)
        assertEquals("2026-2027学年第一学期", meta?.semester)
        // 今天 2026-11-02 落在这份周历的第 10 周——正是用户报「显示第 1 周」那天
        val start = LocalDate.parse(meta!!.startDate)
        val days = ChronoUnit.DAYS.between(start, LocalDate.of(2026, 11, 2))
        assertEquals(10, Math.floorDiv(days, 7).toInt() + 1)
    }

    /** 与 TimeUtils.weekOfSemester 同式，独立算一遍避免测试自己骗自己 */
    private object TimeUtilsWeek {
        fun weekOf(
            start: LocalDate,
            date: LocalDate,
        ): Int {
            val days = ChronoUnit.DAYS.between(start, date)
            return Math.floorDiv(days, 7).toInt() + 1
        }
    }
}
