package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.QingGuoAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QingGuoParseTest {
    /** 青果典型布局：表头「星期一」横跨上午/下午，单元格内 课程/周次/节次/教师/教室 分行了 */
    private val html =
        """
        <html><body>
        <table border="1">
          <tr>
            <td rowspan="2">节次</td>
            <td colspan="2">星期一</td>
            <td>星期二</td>
            <td>星期三</td>
          </tr>
          <tr>
            <td>上午</td><td>下午</td><td></td><td></td>
          </tr>
          <tr>
            <td>1-2节</td>
            <td>高等数学<br>1-16周<br>[01-02节]<br>张三<br>教101</td>
            <td></td>
            <td>大学英语<br>1-16周(单)<br>[01-02节]<br>李四<br>外语楼203</td>
            <td></td>
          </tr>
          <tr>
            <td>3-4节</td>
            <td></td>
            <td>线性代数<br>2-18周(双)<br>[03-04节]<br>王五<br>理科楼A401</td>
            <td></td><td></td>
          </tr>
        </table>
        </body></html>
        """.trimIndent()

    @Test
    fun `parse qingguo table with merged header`() {
        val drafts = HtmlTableScheduleParser.parse(html, 20)
        assertEquals(3, drafts.size)

        val math = drafts.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("张三", math.teacher)
        assertEquals("教101", math.room)
        assertEquals(16, math.weekMask.count { it == '1' })

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(2, english.dayOfWeek)
        // 单周：第 1 周上课、第 2 周不上
        assertTrue(english.weekMask[0] == '1')
        assertTrue(english.weekMask[1] == '0')
        assertEquals("外语楼203", english.room)

        val linear = drafts.first { it.name == "线性代数" }
        assertEquals(1, linear.dayOfWeek)
        assertEquals(3, linear.startSection)
        assertEquals(4, linear.endSection)
        // 双周：第 1 周不上、第 2 周上
        assertTrue(linear.weekMask[0] == '0')
        assertTrue(linear.weekMask[1] == '1')
    }

    /** 课程单元格 rowspan 跨两行时，展开后不应产生重复课程 */
    @Test
    fun `rowspan course cell is deduplicated`() {
        val merged =
            """
            <html><body>
            <table>
              <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
              <tr>
                <td>1-2节</td>
                <td rowspan="2">体育<br>1-16周<br>[01-02节]<br>钱七<br>体育馆</td>
                <td></td><td></td>
              </tr>
              <tr><td>3-4节</td><td></td><td></td></tr>
            </table>
            </body></html>
            """.trimIndent()

        val drafts = HtmlTableScheduleParser.parse(merged, 20)
        assertEquals(1, drafts.size)
        assertEquals("体育", drafts.first().name)
        assertEquals(1, drafts.first().dayOfWeek)
    }

    @Test
    fun `jwweb path is recognized as qingguo`() {
        assertEquals(
            "qingguo",
            JwAdapterRegistry.forUrl("http://jw.xxgc.edu.cn/jwweb/")?.id,
        )
    }

    @Test
    fun `qingguo normalize keeps jwweb base`() {
        assertEquals(
            "https://jw.example.edu.cn/jwweb/",
            QingGuoAdapter.normalizeBase("https://jw.example.edu.cn/jwweb/xskbcx/xskbcx.aspx?xh=1"),
        )
    }
}
