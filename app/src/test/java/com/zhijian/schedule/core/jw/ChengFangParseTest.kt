package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 乘方教务：学期下拉、作息内嵌、新旧两代课表数据 */
class ChengFangParseTest {
    @Test
    fun `chengfang pages match exactly`() {
        assertEquals(
            "chengfang",
            JwAdapterRegistry.forUrl("https://jw.cmc.edu.cn/new/student/xsgrkb/week.page")?.id,
        )
        assertEquals(
            "chengfang",
            JwAdapterRegistry.forUrl("https://jw.jnmc.edu.cn/xsgrkbcx!getXsgrbkList.action")?.id,
        )
        assertNull(JwAdapterRegistry.forUrl("https://jw.example.edu.cn/"))
    }

    @Test
    fun `normalize base keeps deployment directory`() {
        assertEquals(
            "https://jwweb.cmc.edu.cn/new/",
            ChengFangAdapter.normalizeBase("https://jwweb.cmc.edu.cn/new/student/xsgrkb/week.page"),
        )
        assertEquals(
            "https://jw.jnmc.edu.cn/",
            ChengFangAdapter.normalizeBase("https://jw.jnmc.edu.cn/xsgrkbcx!getXsgrbkList.action"),
        )
    }

    /** week.page 的学期下拉：选中项优先，无 selected 时取第一项 */
    @Test
    fun `semester select picks selected option`() {
        val selected = ChengFangAdapter.selectedSemester(weekPage)
        assertEquals("2025-2026-1", selected?.value)
        assertEquals("2025-2026学年第一学期", selected?.label)

        val fallback =
            ChengFangAdapter.selectedSemester(
                "<select id=\"xnxqdm\"><option value=\"a\">A</option></select>",
            )
        assertEquals("a", fallback?.value)
        assertNull(ChengFangAdapter.selectedSemester("<select id=\"other\"></select>"))
    }

    /** 作息：`var businessHours = $.parseJSON('[…]')` → 小节起止 */
    @Test
    fun `business hours parse to section times`() {
        val times = ChengFangAdapter.businessHoursOf(weekPage)
        assertEquals(2, times.size)
        assertEquals(1, times[0].sectionNo)
        assertEquals("08:00", times[0].startTime)
        assertEquals("08:40", times[0].endTime)
        assertEquals("14:00", times[1].startTime)
    }

    /** 新版 getCalendarWeekDatas：按教室分周拆条 + 同位次并周 */
    @Test
    fun `new gen json splits venues and merges weeks`() {
        val drafts = ChengFangAdapter.parseNewGenBody(newCourseJson, 20)
        assertEquals(3, drafts.size)

        val mathA = drafts.first { it.room == "教1-101" }
        assertEquals("高等数学", mathA.name)
        assertEquals(2, mathA.dayOfWeek)
        assertEquals(3, mathA.startSection)
        assertEquals(4, mathA.endSection)
        assertEquals("张三", mathA.teacher)
        assertEquals("11110000000000000000", mathA.weekMask)

        val mathB = drafts.first { it.room == "教1-102" }
        assertEquals("00001000000000000000", mathB.weekMask)

        val english = drafts.first { it.name == "大学英语" }
        assertEquals("11111111111111110000", english.weekMask)
    }

    @Test
    fun `broken new gen json parses to nothing`() {
        assertEquals(0, ChengFangAdapter.parseNewGenBody("not json", 20).size)
        assertEquals(0, ChengFangAdapter.parseNewGenBody("""{"code":500,"msg":"x"}""", 20).size)
    }

    /** 旧版：内嵌 var kbxx = […]；jcdm2 节次串、zcs 周次串（含区间） */
    @Test
    fun `old gen kbxx parses to courses`() {
        val drafts = ChengFangAdapter.parseOldGenBody(oldKbxxHtml, 20)
        assertEquals(2, drafts.size)

        val chem = drafts.first { it.name == "无机化学" }
        assertEquals(1, chem.dayOfWeek)
        assertEquals(1, chem.startSection)
        assertEquals(2, chem.endSection)
        assertEquals("教A-201/202", chem.room)
        assertEquals("11111111000000000000", chem.weekMask)

        val pe = drafts.first { it.name == "体育" }
        // zcs "1,3,5" 单周
        assertEquals("10101000000000000000", pe.weekMask)
    }

    @Test
    fun `broken old gen html parses to nothing`() {
        assertEquals(0, ChengFangAdapter.parseOldGenBody("<html>no data</html>", 20).size)
        assertEquals(0, ChengFangAdapter.parseOldGenBody("var kbxx = [broken;", 20).size)
    }

    private val weekPage =
        """
        <html><body>
        <select id="xnxqdm">
          <option value="2024-2025-2">2024-2025学年第二学期</option>
          <option value="2025-2026-1" selected="selected">2025-2026学年第一学期</option>
        </select>
        <script>var businessHours = $.parseJSON('[{"jcdm":1,"qssj":"08:00:00","jssj":"08:40:00"},{"jcdm":2,"qssj":"14:00:00","jssj":"14:40:00"}]');</script>
        </body></html>
        """.trimIndent()

    private val newCourseJson =
        """
        {"code":0,"data":[
          {"kcmc":"高等数学","teaxms":"张三[讲师]","xq":2,"ps":3,"pe":4,"zc":"1,2,3,4","jcxdmc":"","bapjxcd":"0","jcxdmc2":"教1-101-1,教1-101-2,教1-101-3,教1-101-4,教1-102-5"},
          {"kcmc":"大学英语","teaxms":"李四","xq":3,"ps":1,"pe":2,"zc":"1-16","jcxdmc":"教2-202","bapjxcd":"0","jcxdmc2":""}
        ]}
        """.trimIndent()

    private val oldKbxxHtml =
        """
        <html><body><script>
        var kbxx = [
          {"kcmc":"无机化学","teaxms":"王五,赵六","jcxdmc":"教A-201\202","xq":1,"jcdm2":"1,2","zcs":"1-8"},
          {"kcmc":"体育","teaxms":"钱七","jcxdmc":"操场","xq":5,"jcdm2":"3","zcs":"1,3,5"}
        ];
        </script></body></html>
        """.trimIndent()
}
