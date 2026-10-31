package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.GradeParser
import com.zhijian.schedule.core.jw.GradeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeParserTest {
    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("grade_list_sample.html"))
            .bufferedReader(Charsets.UTF_8)
            .readText()

    @Test
    fun parsesAllRowsFromSample() {
        val records = GradeParser.parse(sample())
        assertEquals(7, records.size)

        val first = records[0]
        assertEquals("2025-2026-1", first.term)
        assertEquals("0911001", first.code)
        assertEquals("高等数学（一）", first.name)
        assertEquals("85", first.score)
        assertEquals("", first.scoreFlag)
        assertEquals(4.0, first.credit, 0.001)
        assertEquals(64, first.hours)
        assertEquals(3.7, first.gpa!!, 0.001)
        assertEquals("", first.retakeTerm)
        assertEquals("考试", first.examMode)
        assertEquals("必修", first.attr)
        assertEquals("公共必修课", first.category)
    }

    @Test
    fun letterGradeAndMissingGpaAreKept() {
        val records = GradeParser.parse(sample())
        val english = records.first { it.name == "大学英语（一）" && it.term == "2025-2026-1" }
        assertEquals("B+", english.score)
        assertEquals(3.0, english.gpa!!, 0.001)

        val pe = records.first { it.name == "体育（一）" }
        assertEquals("优秀", pe.score)
        assertNull(pe.gpa)
    }

    /** 通用表格解析：表头别名定位、列序无关，供青果 / URP / 树维成绩页复用 */
    @Test
    fun genericParseReadsAliasedHeaders() {
        val html =
            """
            <html><body>
            <table id="grid">
              <tr><th>学年学期</th><th>课程代码</th><th>课程名称</th><th>总评成绩</th><th>学分</th><th>绩点</th></tr>
              <tr><td>2025-2026-1</td><td>C001</td><td>线性代数</td><td>91</td><td>2.5</td><td>3.9</td></tr>
              <tr><td>2025-2026-1</td><td>C002</td><td>大学物理</td><td>优秀</td><td>3</td><td></td></tr>
            </table>
            </body></html>
            """.trimIndent()
        val records = GradeParser.parseGeneric(html)
        assertEquals(2, records.size)
        assertEquals("线性代数", records[0].name)
        assertEquals("91", records[0].score)
        assertEquals("2025-2026-1", records[0].term)
        assertEquals("C001", records[0].code)
        assertEquals(2.5, records[0].credit, 0.001)
        assertEquals(3.9, records[0].gpa!!, 0.001)
        assertEquals("优秀", records[1].score)
        assertNull(records[1].gpa)
    }

    @Test
    fun genericParseSkipsNonGradeTablesAndShortRows() {
        // 页面里可能有导航表（无成绩列）在成绩表之前
        val html =
            """
            <html><body>
            <table><tr><td>首页</td><td>选课</td></tr></table>
            <table>
              <tr><th>课程</th><th>成绩</th></tr>
              <tr><td>说明：暂无数据</td></tr>
              <tr><td>高等数学</td><td>88</td></tr>
            </table>
            </body></html>
            """.trimIndent()
        val records = GradeParser.parseGeneric(html)
        assertEquals(listOf("高等数学"), records.map { it.name })
        assertEquals("88", records[0].score)
    }

    @Test
    fun genericParseYieldsEmptyWhenNoGradeTable() {
        assertEquals(0, GradeParser.parseGeneric("<table><tr><td>登录</td></tr></table>").size)
        assertEquals(0, GradeParser.parseGeneric("not html").size)
    }

    @Test
    fun retakeRowKeepsFlagAndRetakeTerm() {
        val records = GradeParser.parse(sample())
        val retake = records.first { it.scoreFlag == "*" }
        assertEquals("大学英语（一）", retake.name)
        assertEquals("2025-2026-2", retake.retakeTerm)
        assertEquals("补考", retake.examNature)
        assertEquals(2.6, retake.gpa!!, 0.001)
    }

    @Test
    fun emptyResultPageYieldsEmptyList() {
        val html =
            """
            <html><body><table id="dataList" class="Nsb_r_list">
            <tr><th>序号</th><th>开课学期</th><th>课程名称</th><th>成绩</th><th>学分</th></tr>
            <tr><td colspan="5">未查询到数据</td></tr>
            </table></body></html>
            """.trimIndent()
        assertTrue(GradeParser.parse(html).isEmpty())
    }

    @Test
    fun loginPageYieldsEmptyList() {
        val html = "<html><body><input type=\"password\" id=\"pwd\"></body></html>"
        assertTrue(GradeParser.parse(html).isEmpty())
    }

    @Test
    fun statsComputeWeightedGpaAndAverage() {
        val stats = GradeParser.stats(GradeParser.parse(sample()))
        assertEquals(7, stats.courseCount)
        assertEquals(20.0, stats.totalCredits, 0.001)
        // 有绩点的 5 门：4*3.7 + 3*3 + 4*4 + 4*4 + 3*2.6 = 63.6，学分 18
        assertEquals(63.6 / 18, stats.weightedGpa!!, 0.001)
        // 百分制 2 门：85*4 + 92*4 = 708，学分 8
        assertEquals(708.0 / 8, stats.weightedAvg!!, 0.001)
        assertEquals(0, stats.failCount)
    }

    @Test
    fun statsOnSingleTermFilter() {
        val records = GradeParser.parse(sample()).filter { it.term == "2025-2026-2" }
        val stats = GradeParser.stats(records)
        assertEquals(2, stats.courseCount)
        assertEquals(7.0, stats.totalCredits, 0.001)
        // 4*4 + 3*2.6 = 23.8，学分 7
        assertEquals(23.8 / 7, stats.weightedGpa!!, 0.001)
    }

    @Test
    fun failDetectionByScoreAndZeroGpa() {
        val failByScore =
            GradeRecord(
                term = "2025-2026-1",
                code = "",
                name = "高等数学（一）",
                score = "不及格",
                scoreFlag = "",
                credit = 4.0,
                hours = null,
                gpa = null,
                retakeTerm = "",
                examMode = "",
                examNature = "",
                attr = "",
                category = "",
            )
        assertTrue(GradeParser.isFail(failByScore))
        val failByGpa = failByScore.copy(score = "38", gpa = 0.0)
        assertTrue(GradeParser.isFail(failByGpa))
        assertEquals(1, GradeParser.stats(listOf(failByScore)).failCount)
    }
}
