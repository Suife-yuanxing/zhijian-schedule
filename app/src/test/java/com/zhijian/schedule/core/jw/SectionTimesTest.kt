package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 作息时间抽取的纯逻辑：大节区间拆分、HTML 表格时间列识别、归一化补洞。
 *
 * 这些函数决定「抓完课表后作息时间变成什么」，所以先把边界钉死：
 * 课间按剩余分钟均分、时间倒挂整批丢弃、缺的节次用兜底值补齐。
 */
class SectionTimesTest {
    private fun hm(
        h: Int,
        m: Int,
    ): LocalTime = LocalTime.of(h, m)

    @Test
    fun `split two sections from a 95 minute period uses 5 minute break`() {
        val out = SectionTimes.splitPeriod(hm(8, 20), hm(9, 55), sectionsInPeriod = 2)
        assertEquals(listOf("08:20" to "09:05", "09:10" to "09:55"), out)
    }

    @Test
    fun `split two sections from a 90 minute period has no break`() {
        val out = SectionTimes.splitPeriod(hm(10, 0), hm(11, 30), sectionsInPeriod = 2)
        assertEquals(listOf("10:00" to "10:45", "10:45" to "11:30"), out)
    }

    @Test
    fun `split three sections spreads the leftover evenly`() {
        // 3 x 45 = 135，总长 145 => 两个课间各 5 分钟
        val out = SectionTimes.splitPeriod(hm(8, 0), hm(10, 25), sectionsInPeriod = 3)
        assertEquals(listOf("08:00" to "08:45", "08:50" to "09:35", "09:40" to "10:25"), out)
    }

    @Test
    fun `split ignores a period too short for its sections`() {
        assertTrue(SectionTimes.splitPeriod(hm(8, 0), hm(8, 30), sectionsInPeriod = 2).isEmpty())
        assertTrue(SectionTimes.splitPeriod(hm(9, 0), hm(8, 0), sectionsInPeriod = 2).isEmpty())
        assertTrue(SectionTimes.splitPeriod(hm(8, 0), hm(10, 0), sectionsInPeriod = 0).isEmpty())
    }

    /** 青果 / 树维 classic / URP 老版：每行一个小节，"时间"列直接给起止 */
    private val rowPerSection =
        """
        <table>
          <tr><th>节次</th><th>时间</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          <tr><td>1</td><td>08:20-09:05</td><td></td><td></td><td></td></tr>
          <tr><td>2</td><td>09:10-09:55</td><td></td><td></td><td></td></tr>
          <tr><td>3</td><td>10:10-10:55</td><td></td><td></td><td></td></tr>
          <tr><td>4</td><td>11:00-11:45</td><td></td><td></td><td></td></tr>
        </table>
        """.trimIndent()

    @Test
    fun `extract per-section times from a table time column`() {
        val out = SectionTimes.fromHtml(rowPerSection)
        assertEquals(4, out.size)
        assertEquals(1, out[0].sectionNo)
        assertEquals("08:20", out[0].startTime)
        assertEquals("09:55", out[1].endTime)
        assertEquals(4, out[3].sectionNo)
        assertEquals("11:45", out[3].endTime)
    }

    /** 强智 jsxsd：行首是「第一大节 08:20-09:55」，一个大节含两小节 */
    private val periodRowTable =
        """
        <table id="kbtable">
          <tr><th>&nbsp;</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          <tr><th>第一大节<br>08:20-09:55</th><td></td><td></td><td></td></tr>
          <tr><th>第二大节<br>10:10-11:45</th><td></td><td></td><td></td></tr>
          <tr><th>第三大节<br>14:20-15:55</th><td></td><td></td><td></td></tr>
        </table>
        """.trimIndent()

    @Test
    fun `extract and split period times from a section label column`() {
        val out = SectionTimes.fromHtml(periodRowTable)
        assertEquals(6, out.size)
        assertEquals("08:20", out[0].startTime)
        assertEquals("09:05", out[0].endTime)
        assertEquals("09:10", out[1].startTime)
        assertEquals("14:20", out[4].startTime)
        assertEquals("15:55", out[5].endTime)
    }

    /** 强智另一种写法：时间分两行，中间没有横杠 */
    private val stackedTime =
        """
        <table id="kbtable">
          <tr><td>时间</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
          <tr><td>1-2节</td><td>08:20<br>09:55</td><td></td><td></td></tr>
          <tr><td>3-4节</td><td>10:10<br>11:45</td><td></td><td></td></tr>
        </table>
        """.trimIndent()

    @Test
    fun `extract period times written on two lines`() {
        val out = SectionTimes.fromHtml(stackedTime)
        assertEquals(4, out.size)
        assertEquals("08:20", out[0].startTime)
        assertEquals("09:55", out[1].endTime)
        assertEquals("11:45", out[3].endTime)
    }

    @Test
    fun `table without any time text yields nothing`() {
        val html =
            """
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>1</td><td>高等数学</td><td></td><td></td></tr>
            </table>
            """.trimIndent()
        assertTrue(SectionTimes.fromHtml(html).isEmpty())
    }

    @Test
    fun `extraction refuses times that go backwards`() {
        val html =
            """
            <table>
              <tr><th>节次</th><th>时间</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>1</td><td>10:00-10:45</td><td></td><td></td><td></td></tr>
              <tr><td>2</td><td>08:00-08:45</td><td></td><td></td><td></td></tr>
            </table>
            """.trimIndent()
        assertTrue(SectionTimes.fromHtml(html).isEmpty())
    }

    @Test
    fun `normalize fills the gaps from the fallback list`() {
        val extracted =
            listOf(
                SectionTimeDraft(1, "08:00", "08:45"),
                SectionTimeDraft(2, "08:50", "09:35"),
            )
        val fallback =
            listOf(
                SectionTimeDraft(1, "08:20", "09:05"),
                SectionTimeDraft(2, "09:10", "09:55"),
                SectionTimeDraft(3, "10:10", "10:55"),
                SectionTimeDraft(4, "11:00", "11:45"),
            )
        val out = SectionTimes.normalize(extracted, fallback, neededCount = 4)
        assertEquals(listOf(1, 2, 3, 4), out.map { it.sectionNo })
        assertEquals("08:00", out[0].startTime)
        assertEquals("10:10", out[2].startTime)
        assertEquals("11:45", out[3].endTime)
    }

    @Test
    fun `normalize extends past the fallback when a course needs more sections`() {
        val extracted =
            listOf(
                SectionTimeDraft(1, "08:00", "08:45"),
                SectionTimeDraft(2, "08:50", "09:35"),
            )
        val fallback = listOf(SectionTimeDraft(1, "08:20", "09:05"))
        val out = SectionTimes.normalize(extracted, fallback, neededCount = 3)
        assertEquals(3, out.size)
        assertEquals("09:40", out[2].startTime)
        assertEquals("10:25", out[2].endTime)
    }

    @Test
    fun `normalize trims sections the timetable no longer needs`() {
        val extracted = (1..4).map { SectionTimeDraft(it, "08:0$it", "08:5$it") }
        val fallback = (1..12).map { SectionTimeDraft(it, "08:00", "09:00") }
        val out = SectionTimes.normalize(extracted, fallback, neededCount = 4)
        assertEquals(4, out.size)
    }

    @Test
    fun `normalize keeps the fallback when nothing was extracted`() {
        val fallback = listOf(SectionTimeDraft(1, "08:20", "09:05"))
        val out = SectionTimes.normalize(emptyList(), fallback, neededCount = 1)
        assertEquals(fallback, out)
    }
}
