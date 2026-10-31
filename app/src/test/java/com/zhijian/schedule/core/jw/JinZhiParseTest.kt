package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Test

/** 金智教务：TaskActivity JS 解析、semesterCalendar、/eams/ 指纹与树维的区分 */
class JinZhiParseTest {
    /** 页面嵌 JS 的经典形态：actTeachers join 表达式 + 位图周次 + 连续 index */
    @Test
    fun `task activity blocks parse to courses`() {
        val html = eamsPage
        val drafts = JinZhiAdapter.parsePage(html, 20)
        assertEquals(2, drafts.size)

        val math = drafts.first { it.name == "高等数学(一)" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("张三/李四", math.teacher)
        assertEquals("教1-101", math.room)
        // 位图下标 0 占位：01111…11100000 → 1-16 周
        assertEquals("11111111111111110000", math.weekMask)

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(3, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(3, english.endSection)
        assertEquals("王五", english.teacher)
        // 0101… → 单周 1,3,…,15
        assertEquals("10101010101010100000", english.weekMask)
    }

    /** 课名带逗号时不能被裸 split(',') 切开 */
    @Test
    fun `course name containing comma survives`() {
        val html =
            """
            var unitCount = 12;
            activity = new TaskActivity(1, "赵六", "CS01", "数据结构,C++版", "JX1", "机房", "0111111111111111100000", "9", "");
            index = 4 * unitCount + 5;
            """.trimIndent()
        val drafts = JinZhiAdapter.parsePage(html, 20)
        assertEquals(1, drafts.size)
        assertEquals("数据结构,C++版", drafts[0].name)
        assertEquals(5, drafts[0].dayOfWeek)
    }

    /** 纯数字 index 按 unitCount 分解；教师 -1 与「停课」是教务的停课标记 */
    @Test
    fun `linear index and stop course markers`() {
        val html =
            """
            var unitCount = 12;
            activity = new TaskActivity(2, "钱七", "P1", "大学物理", "JX3", "教3-301", "0111111111111111100000", "3", "");
            index = 27;
            activity = new TaskActivity(3, "-1", "X1", "自习", "JX9", "停课", "0111111111111111100000", "4", "");
            index = 5 * unitCount + 1;
            """.trimIndent()
        val drafts = JinZhiAdapter.parsePage(html, 20)
        assertEquals(1, drafts.size)
        assertEquals("大学物理", drafts[0].name)
        // 27 = 2*12+3 → 周三第 4 节
        assertEquals(3, drafts[0].dayOfWeek)
        assertEquals(4, drafts[0].startSection)
    }

    /** 没有 unitCount 的页面不猜默认值 */
    @Test
    fun `page without unit count parses to nothing`() {
        val html = """activity = new TaskActivity(1, "a", "b", "课", "r", "room", "0111", "1", "");"""
        assertEquals(0, JinZhiAdapter.parsePage(html, 20).size)
    }

    @Test
    fun `semester calendar extracts names`() {
        val body =
            """
            semesters: {"2020-2021":[{id:61,schoolYear:"2020-2021",name:"1"},{id:62,schoolYear:"2020-2021",name:"2"}],
                        "2021-2022":[{id:63,schoolYear:"2021-2022",name:"1"}]}
            """.trimIndent()
        assertEquals(listOf("2020-2021-1", "2020-2021-2", "2021-2022-1"), JinZhiAdapter.parseSemesterNames(body))
    }

    @Test
    fun `normalize base keeps eams path`() {
        val withAction = "https://aao.nuaa.edu.cn/eams/courseTableStudent!courseTable.action?x=1"
        assertEquals("https://aao.nuaa.edu.cn/eams/", JinZhiAdapter.normalizeBase(withAction))
        assertEquals("https://jw.xx.edu.cn/eams/", JinZhiAdapter.normalizeBase("https://jw.xx.edu.cn/eams"))
        assertEquals("https://jw.xx.edu.cn/", JinZhiAdapter.normalizeBase("https://jw.xx.edu.cn/portal/home"))
    }

    /** 金智靠 courseTableStudent 端点指纹赢过树维的 /eams/ 兜底；树维指纹不被抢走 */
    @Test
    fun `jinzhi and shuwei do not steal each other`() {
        assertEquals(
            "jinzhi",
            JwAdapterRegistry.forUrl("https://aao.nuaa.edu.cn/eams/courseTableStudent!courseTable.action")?.id,
        )
        // teach/grade 一类成绩页：树维的 /eams/ 强指纹（95）压过金智端点（90），
        // 树维的成绩候选解析同样能兜住金智的通用表头，这里让树维赢
        assertEquals(
            "shuwei_classic",
            JwAdapterRegistry.forUrl("https://aao.nuaa.edu.cn/eams/teach/grade/course/person!search.action")?.id,
        )
        val uestc = "https://eams.uestc.edu.cn/eams/coursetable/courseTableForStd!courseTable.action"
        assertEquals("shuwei_classic", JwAdapterRegistry.forUrl(uestc)?.id)
    }

    private val eamsPage =
        """
        <div id="ExportA">
        var unitCount = 12;
        var actTeachers = [{id:7396,name:"张三",lab:true},{id:7397,name:"李四",lab:true}];
        var actTeacherName = actTeachers.map(function(t){return t.name});
        activity = new TaskActivity(7396, actTeacherName.join(','), "MATH01",
            "高等数学(一)", "JX101", "教1-101", "0111111111111111100000", "1001", "");
        index = 0 * unitCount + 0;
        index = 0 * unitCount + 1;
        activity = new TaskActivity(7398, "王五", "ENG01", "大学英语", "JX202", "教2-202", "0101010101010101000000", "1002", "");
        index = 2 * unitCount + 2;
        </div>
        """.trimIndent()
}
