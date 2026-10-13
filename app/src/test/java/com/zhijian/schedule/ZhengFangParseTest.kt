package com.zhijian.schedule

import com.zhijian.schedule.core.jw.ZhengFangAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhengFangParseTest {

    /** 旧版布局：大节行 + 表头星期 + 单元格多课程分隔线（保留兼容） */
    private val html = """
        <html><body>
        <table id="kbtable">
          <tr>
            <td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td>
          </tr>
          <tr>
            <td>1-2节</td>
            <td>
              包装材料与检验<br>张三<br>1-16周<br>教305
            </td>
            <td></td>
            <td>大学英语<br>李四<br>1-16周(单)<br>教101</td>
            <td></td><td></td>
          </tr>
          <tr>
            <td>3-4节</td>
            <td></td>
            <td>
              高等数学<br>王五<br>1-8周<br>理A401
              ----------------------
              线性代数<br>赵六<br>9-16周<br>理A402
            </td>
            <td></td><td></td><td>体育<br>钱七<br>2-18周(双)<br>体育馆</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parse zhengfang kbtable`() {
        val drafts = ZhengFangAdapter.parse(html, 20)
        assertEquals(5, drafts.size)

        val packaging = drafts.first { it.name == "包装材料与检验" }
        assertEquals(1, packaging.dayOfWeek)
        assertEquals(1, packaging.startSection)
        assertEquals(2, packaging.endSection)
        assertEquals("张三", packaging.teacher)
        assertEquals("教305", packaging.room)
        assertEquals(16, packaging.weekMask.count { it == '1' })

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(3, english.dayOfWeek)
        // 单周：第 1 周上课、第 2 周不上
        assertTrue(english.weekMask[0] == '1')
        assertTrue(english.weekMask[1] == '0')
    }

    @Test
    fun `parse multiple courses in one cell`() {
        val drafts = ZhengFangAdapter.parse(html, 20)
        val math = drafts.first { it.name == "高等数学" }
        val linear = drafts.first { it.name == "线性代数" }
        assertEquals(2, math.dayOfWeek)
        assertEquals(3, math.startSection)
        assertEquals(4, math.endSection)
        assertEquals("理A401", math.room)
        assertEquals("理A402", linear.room)
        assertEquals(8, linear.weekMask.count { it == '1' })
    }

    @Test
    fun `normalize base url keeps jsxsd path`() {
        assertEquals(
            "https://jw.dgpt.edu.cn/jsxsd/",
            ZhengFangAdapter.normalizeBase("https://jw.dgpt.edu.cn/jsxsd/xskb/xskbList.do?xnxqh=1"),
        )
        // 无 /jsxsd/ 路径的链接（裸域名、统一认证登录入口）统一回落到教务基地址
        assertEquals(
            "https://jw.dgpt.edu.cn/jsxsd/",
            ZhengFangAdapter.normalizeBase("jw.dgpt.edu.cn"),
        )
        assertEquals(
            "https://jw.dgpt.edu.cn/jsxsd/",
            ZhengFangAdapter.normalizeBase("http://jw.dgpt.edu.cn/jsxsd/sso.jsp"),
        )
        assertEquals(
            "https://jw.dgpt.edu.cn/jsxsd/",
            ZhengFangAdapter.normalizeBase("https://authserver.dgpt.edu.cn/authserver/login?service=x"),
        )
        // WebVPN 包装地址保留包装前缀，抓取路径拼在其后
        assertEquals(
            "https://webvpn.dgpt.edu.cn/https/abc123/jsxsd/",
            ZhengFangAdapter.normalizeBase("https://webvpn.dgpt.edu.cn/https/abc123/jsxsd/xskb/xskb_list.do"),
        )
    }

    /** rowspan 布局：大节行含 rowspan 的节次/时间格（9 节点），第二小节行仅 7 个课程格 */
    private val rowspanHtml = """
        <html><body>
        <table id="kbtable">
          <tr>
            <td colspan="2">时间</td>
            <td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td>
          </tr>
          <tr>
            <td rowspan="2">1-2节</td>
            <td rowspan="2">08:20<br>09:55</td>
            <td>包装材料与检验<br>张三<br>1-16周<br>教305</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td>
            <td>大学英语<br>李四<br>1-16周(单)<br>教101</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td rowspan="2">3-4节</td>
            <td rowspan="2">10:10<br>11:45</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td rowspan="2">5-6节</td>
            <td rowspan="2">14:20<br>15:55</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td rowspan="2">7-8节</td>
            <td rowspan="2">16:05<br>17:40</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td rowspan="2">9-10节</td>
            <td rowspan="2">18:30<br>20:05</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td rowspan="2">11-12节</td>
            <td rowspan="2">20:15<br>21:50</td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parse rowspan layout aligns cells to weekday columns`() {
        val drafts = ZhengFangAdapter.parse(rowspanHtml, 20)
        assertEquals(2, drafts.size)

        val packaging = drafts.first { it.name == "包装材料与检验" }
        assertEquals(1, packaging.dayOfWeek)
        assertEquals(1, packaging.startSection)
        assertEquals(2, packaging.endSection)
        assertEquals("张三", packaging.teacher)
        assertEquals("教305", packaging.room)

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(3, english.dayOfWeek)
        assertEquals(2, english.startSection)
        assertEquals(2, english.endSection)
    }

    @Test
    fun `nbsp empty cells produce no drafts`() {
        val drafts = ZhengFangAdapter.parse(rowspanHtml, 20)
        assertTrue(drafts.none { it.name.isBlank() })
        assertTrue(drafts.none { it.name == "\u00A0" })
    }

    /** 新版页面：隐藏的 div.kbcontent1 是同一格的重复渲染（无老师），只解析可见 div.kbcontent */
    private val divCellHtml = """
        <html><body>
        <table id="kbtable">
          <tr>
            <td>时间</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td>
          </tr>
          <tr>
            <td>1-2节</td>
            <td>
              <div class="kbcontent1" style="display: none;">高等数学<br>1-8周<br>理A401</div>
              <div class="kbcontent">高等数学<br>王五<br>1-8周<br>理A401<br>----------------------<br>线性代数<br>赵六<br>9-16周<br>理A402</div>
            </td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `visible kbcontent parsed, hidden kbcontent1 ignored`() {
        val drafts = ZhengFangAdapter.parse(divCellHtml, 20)
        // kbcontent1 不参与解析，否则每门课会多出无老师的重复副本
        assertEquals(2, drafts.size)
        val math = drafts.first { it.name == "高等数学" }
        val linear = drafts.first { it.name == "线性代数" }
        assertEquals(1, math.dayOfWeek)
        assertEquals("王五", math.teacher)
        assertEquals("理A401", math.room)
        assertEquals("理A402", linear.room)
        assertEquals(8, math.weekMask.count { it == '1' })
    }

    /** 强智真实结构（2026-10-13 实抓）：th 节次列「第一大节」+ font 标注行 + 方括号节次区间 */
    private val qiangzhiHtml = """
        <html><body>
        <table id="kbtable" class="Nsb_r_list Nsb_table">
          <tbody><tr>
            <th>&nbsp;</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
          </tr>
          <tr>
            <th>第一大节<br>08:20-09:55</th>
            <td>
              <div class="kbcontent1" style="display: none;">包装材料与检测<br><font title="周次(节次)">12(周)</font><br><font title="教室">6A308</font><br></div>
              <div class="kbcontent">包装材料与检测<br><font title="老师">张峻岭</font><br><font title="周次(节次)">12(周)[01-02-03-04节]</font><br><font title="教室">6A308</font><br>---------------------<br>包装职业技能实训A<br><font title="老师">许霞</font><br><font title="周次(节次)">2(周)[01-02-03-04节]</font><br><font title="教室">8D404</font><br></div>
            </td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <th>第二大节<br>10:10-11:45</th>
            <td>&nbsp;</td>
            <td><div class="kbcontent">大学英语<br><font title="老师">李四</font><br><font title="周次(节次)">2-19(周)[03-04节]</font><br><font title="教室">6B201</font><br></div></td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parse qiangzhi kbtable with th section column and bracket sections`() {
        val drafts = ZhengFangAdapter.parse(qiangzhiHtml, 20)
        assertEquals(3, drafts.size)

        val pkg = drafts.first { it.name == "包装材料与检测" }
        assertEquals(1, pkg.dayOfWeek)
        // [01-02-03-04节] → 1..4
        assertEquals(1, pkg.startSection)
        assertEquals(4, pkg.endSection)
        assertEquals("张峻岭", pkg.teacher)
        assertEquals("6A308", pkg.room)
        // 第 12 周上课
        assertEquals(1, pkg.weekMask.count { it == '1' })
        assertTrue(pkg.weekMask[11] == '1')

        val intern = drafts.first { it.name == "包装职业技能实训A" }
        assertEquals(1, intern.dayOfWeek)
        assertEquals("许霞", intern.teacher)
        assertEquals("8D404", intern.room)
        assertTrue(intern.weekMask[1] == '1')
        assertEquals(1, intern.weekMask.count { it == '1' })

        val eng = drafts.first { it.name == "大学英语" }
        assertEquals(2, eng.dayOfWeek)
        // [03-04节] → 3..4
        assertEquals(3, eng.startSection)
        assertEquals(4, eng.endSection)
        // 2-19 周共 18 周
        assertEquals(18, eng.weekMask.count { it == '1' })
    }
}
