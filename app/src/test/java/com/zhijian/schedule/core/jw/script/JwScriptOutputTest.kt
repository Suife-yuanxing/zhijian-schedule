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
