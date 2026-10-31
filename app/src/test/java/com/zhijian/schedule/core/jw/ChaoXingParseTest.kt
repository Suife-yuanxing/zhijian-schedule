package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 超星教务：租户域识别、sdpkkbList 课表 JSON、jqGrid 成绩 JSON */
class ChaoXingParseTest {
    @Test
    fun `tenant domains match exactly`() {
        assertEquals("chaoxing", JwAdapterRegistry.forUrl("https://hbut.jw.chaoxing.com/admin/")?.id)
        assertEquals("chaoxing", JwAdapterRegistry.forUrl("https://xxx.fanya.chaoxing.com/admin/login")?.id)
    }

    @Test
    fun `non tenant hosts do not match`() {
        assertNull(JwAdapterRegistry.forUrl("https://jw.example.edu.cn/admin/"))
        assertNull(ChaoXingAdapter.tenantRoot("https://jw.example.edu.cn/"))
    }

    @Test
    fun `tenant root strips path and query`() {
        assertEquals(
            "https://hbut.jw.chaoxing.com/",
            ChaoXingAdapter.tenantRoot("https://hbut.jw.chaoxing.com/admin/pkgl/xskb?xnxq=1"),
        )
    }

    /** 门户页写了学期就用它；日期推算的两个候选也总在列 */
    @Test
    fun `semester candidates prefer page and always include derived`() {
        val fromPage = ChaoXingAdapter.semesterCandidates("<div>2025-2026-1 学期课表</div>")
        assertTrue(fromPage.contains("2025-2026-1"))
        assertTrue(fromPage.first().endsWith("-1") || fromPage.first().endsWith("-2"))

        val plain = ChaoXingAdapter.semesterCandidates("<html></html>")
        assertEquals(2, plain.size)
        assertTrue(plain[0].endsWith("-1") && plain[1].endsWith("-2"))
    }

    /** 相同天 + 同名 + 同周次的连续小节合并成连堂；HTML 片段剥离成纯文本 */
    @Test
    fun `timetable items merge consecutive sections`() {
        val body =
            """
            {"ret":0,"data":[
              {"kcmc":"<a>高等数学</a>","tmc":"张三","croommc":"JX101","xingqi":2,"djc":3,"zcstr":"1,2,3,4"},
              {"kcmc":"<a>高等数学</a>","tmc":"张三","croommc":"JX101","xingqi":2,"djc":4,"zcstr":"1,2,3,4"},
              {"kcmc":"大学英语","tmc":"李四","croommc":"JX9","xingqi":2,"djc":5,"zcstr":"1,2,3,4"},
              {"kcmc":"体育","tmc":"王五","croommc":"操场","xingqi":1,"djc":1,"zcstr":"1-8"}
            ]}
            """.trimIndent()
        val drafts = ChaoXingAdapter.parseTimetable(body, 20)
        assertEquals(3, drafts.size)

        val math = drafts.first { it.name == "高等数学" }
        assertEquals(2, math.dayOfWeek)
        assertEquals(3, math.startSection)
        assertEquals(4, math.endSection)
        assertEquals("张三", math.teacher)
        assertEquals("JX101", math.room)
        assertEquals("11110000000000000000", math.weekMask)

        // 周次不同不合并：篮球课 1-8 周，掩码长度 20
        val pe = drafts.first { it.name == "体育" }
        assertEquals("11111111000000000000", pe.weekMask)
    }

    @Test
    fun `broken timetable json parses to nothing`() {
        assertEquals(0, ChaoXingAdapter.parseTimetable("not json", 20).size)
        assertEquals(0, ChaoXingAdapter.parseTimetable("""{"ret":401,"msg":"请登录"}""", 20).size)
    }

    /** jqGrid rows：总评成绩优先，空总评用特殊成绩文案；补考进 scoreFlag */
    @Test
    fun `jqgrid rows map to grade records`() {
        val body =
            """
            {"rows":[
              {"xnxq":"2024-2025-1","kcmc":"大学英语","xf":"3","zhcj":"86","tscjzwmc":"","sfbk":"0"},
              {"xnxq":"2024-2025-1","kcmc":"体育","xf":"1","zhcj":"","tscjzwmc":"缺考","sfbk":"0"},
              {"xnxq":"2024-2025-2","kcmc":"高等数学","xf":"4","zhcj":"55","tscjzwmc":"","sfbk":"1"}
            ],"records":3}
            """.trimIndent()
        val records = ChaoXingAdapter.parseGrades(body)
        assertEquals(3, records.size)
        assertEquals("86", records[0].score)
        assertEquals(3.0, records[0].credit, 0.001)
        assertEquals("", records[0].scoreFlag)
        assertEquals("缺考", records[1].score)
        assertEquals("55", records[2].score)
        assertEquals("补考", records[2].scoreFlag)
        assertEquals("2024-2025-2", records[2].term)
    }

    @Test
    fun `broken grades json parses to nothing`() {
        assertEquals(0, ChaoXingAdapter.parseGrades("not json").size)
        assertEquals(0, ChaoXingAdapter.parseGrades("""{"rows":[]}""").size)
    }
}
