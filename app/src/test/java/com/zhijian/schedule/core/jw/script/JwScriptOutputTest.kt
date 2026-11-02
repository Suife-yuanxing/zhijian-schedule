package com.zhijian.schedule.core.jw.script

import com.zhijian.schedule.core.util.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwScriptOutputTest {
    @Test
    fun parsesCoursesWithWeeksString() {
        val json =
            """
            {"courses":[
              {"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8,10-16周","teacher":"张三","room":"实B-301"}
            ]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is JwScriptOutput.ParseResult.Ok)
        r as JwScriptOutput.ParseResult.Ok
        assertEquals(1, r.drafts.size)
        val d = r.drafts.first()
        assertEquals("高等数学", d.name)
        assertEquals(1, d.dayOfWeek)
        assertEquals(1, d.startSection)
        assertEquals(2, d.endSection)
        assertEquals(15, d.weekMask.count { it == '1' })
        assertEquals("张三", d.teacher)
        assertEquals("实B-301", d.room)
    }

    @Test
    fun parsesWeeksAsIntArrayAndCoercesSections() {
        val json =
            """
            {"courses":[{"name":"英语","day":"3","startSection":13,"endSection":14,"weeks":[1,2,3]}],
             "totalWeeks":20,"warnings":["第 2 条格式不对，已跳过"]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is JwScriptOutput.ParseResult.Ok)
        r as JwScriptOutput.ParseResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals(3, r.drafts.first().dayOfWeek)
        assertEquals(12, r.drafts.first().endSection)
        assertEquals("1-3周", WeekMask.toExpression(r.drafts.first().weekMask))
        assertEquals(listOf("第 2 条格式不对，已跳过"), r.warnings)
        assertEquals(20, r.totalWeeks)
    }

    @Test
    fun dropsInvalidEntriesWithWarnings() {
        val json =
            """
            {"courses":[
              {"name":"","day":1,"startSection":1,"endSection":2},
              {"name":"坏天","day":9,"startSection":1,"endSection":2},
              {"name":"好课","day":2,"startSection":3,"endSection":4}
            ]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is JwScriptOutput.ParseResult.Ok)
        r as JwScriptOutput.ParseResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals("好课", r.drafts.first().name)
        assertEquals(2, r.warnings.size)
    }

    @Test
    fun rejectsEmptyAndOversizedAndBadEnvelope() {
        assertTrue(JwScriptOutput.parse("{\"courses\":[]}", 20) is JwScriptOutput.ParseResult.Invalid)
        assertTrue(
            JwScriptOutput.parse(
                "{\"courses\":" + List(501) { "{\"name\":\"x\",\"day\":1}" }.joinToString(",", "[", "]") + "}",
                20,
            ) is JwScriptOutput.ParseResult.Invalid,
        )
        assertTrue(JwScriptOutput.parse("not json", 20) is JwScriptOutput.ParseResult.Invalid)
    }

    /**
     * 脚本声明的 totalWeeks 要决定掩码长度。
     *
     * 本地还停在 16 周、脚本按本校校历声明 18 周时，若仍用本地那个值建掩码，
     * "17-18周" 会被截成空——声明的那两周正好是被吃掉的那两周。
     */
    @Test
    fun declaredTotalWeeksDrivesMaskLength() {
        val json =
            """
            {"courses":[{"name":"形势与政策","day":5,"startSection":9,"endSection":10,"weeks":"17-18周"}],
             "totalWeeks":18}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 16) as JwScriptOutput.ParseResult.Ok
        assertEquals(18, r.totalWeeks)
        val mask = r.drafts.first().weekMask
        assertEquals(18, mask.length)
        assertTrue(WeekMask.contains(mask, 17))
        assertTrue(WeekMask.contains(mask, 18))
    }

    /** 脚本没声明时仍按本地总周数建掩码，行为不变 */
    @Test
    fun missingTotalWeeksKeepsLocalLength() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20) as JwScriptOutput.ParseResult.Ok
        assertNull(r.totalWeeks)
        val mask = r.drafts.first().weekMask
        assertEquals(20, mask.length)
    }

    /**
     * 脚本声明的 startDate 要跟着进元数据。
     *
     * 这一条守着「学期第 10 周显示成第 1 周」那个回归：课表页只给周次，
     * 开学日期只能靠来源声明，缺了它周次映射全偏。
     */
    @Test
    fun declaredStartDateGoesIntoMeta() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "totalWeeks":20,"startDate":"2026-08-31"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals("2026-08-31", r.startDate)
        val meta = r.metaOrNull()
        assertEquals("2026-08-31", meta?.startDate)
        assertEquals(20, meta?.totalWeeks)
    }

    /** 只声明开学日期、不声明总周数：总周数留 0 表示不覆盖 */
    @Test
    fun startDateAloneStillYieldsMeta() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "startDate":"2026-08-31"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        val meta = r.metaOrNull()
        assertEquals("2026-08-31", meta?.startDate)
        assertEquals(0, meta?.totalWeeks)
    }

    /** 非 ISO 写法归一化成 ISO：脚本作者常写 2026/8/31 */
    @Test
    fun slashAndShortDateNormalizedToIso() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "startDate":"2026/8/31"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals("2026-08-31", r.startDate)
    }

    /** 日期不合法就丢弃并记 warning，不能把原文写进库 */
    @Test
    fun invalidStartDateDroppedWithWarning() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "startDate":"不是日期"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertNull(r.startDate)
        assertTrue(r.warnings.any { it.contains("开学日期") })
        assertNull(r.metaOrNull())
    }

    /** 两样都没声明：没有元数据，本地值原样保留 */
    @Test
    fun noMetaWhenNeitherDeclared() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertNull(r.metaOrNull())
    }

    /** 正方 / 青果没有校历可读，但学期名拿得到——不回传等于让用户白填一个字段 */
    @Test
    fun declaredSemesterGoesIntoMeta() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "semester":"2026-2027学年第二学期"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals("2026-2027学年第二学期", r.metaOrNull()?.semester)
        // 只给学期名时其余字段保持「未声明」，不能顺手写成 0 周 / 空日期以外的值
        assertEquals("", r.metaOrNull()?.startDate)
        assertEquals(0, r.metaOrNull()?.totalWeeks)
    }

    /** 空白的 semester 视为没给，否则会用空串把库里的学期名清掉 */
    @Test
    fun blankSemesterIsNotDeclared() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "semester":"   "}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertNull(r.metaOrNull())
    }

    /** 学期名是给人看的字符串，各校写法不同，原样收下不做归一 */
    @Test
    fun semesterIsKeptVerbatim() {
        val json =
            """
            {"courses":[{"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8周"}],
             "semester":"2026-2027-1"}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals("2026-2027-1", r.metaOrNull()?.semester)
    }

    @Test
    fun mergesSplitRowsBySameKey() {
        val json =
            """
            {"courses":[
              {"name":"体育","day":4,"startSection":3,"endSection":4,"weeks":"1-8周","teacher":"李","room":"gym"},
              {"name":"体育","day":4,"startSection":3,"endSection":4,"weeks":"9-16周","teacher":"李","room":"gym"}
            ]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals(
            16,
            r.drafts
                .first()
                .weekMask
                .count { it == '1' },
        )
    }
}
