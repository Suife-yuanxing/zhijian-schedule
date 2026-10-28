package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本引擎也要能带作息回来：sectionTimes 字段可选、逐条校验、坏条目丢弃 */
class JwScriptSectionTimesTest {
    @Test
    fun `script section times are normalized and sorted`() {
        val json =
            """
            {"courses":[{"name":"高数","day":1,"startSection":1,"endSection":2,"weeks":"1-16周"}],
             "sectionTimes":[
               {"section":2,"start":"9:5","end":"10：40"},
               {"section":1,"start":"08:20","end":"09:05"}
             ]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals(2, r.sectionTimes.size)
        assertEquals(1, r.sectionTimes[0].sectionNo)
        assertEquals("08:20", r.sectionTimes[0].startTime)
        assertEquals("09:05", r.sectionTimes[0].endTime)
        assertEquals("09:05", r.sectionTimes[1].startTime)
        assertEquals("10:40", r.sectionTimes[1].endTime)
    }

    @Test
    fun `bad section time entries are dropped with warnings`() {
        val json =
            """
            {"courses":[{"name":"高数","day":1,"startSection":1,"endSection":2,"weeks":"1-16周"}],
             "sectionTimes":[
               {"section":1,"start":"08:20","end":"09:05"},
               {"section":0,"start":"08:20","end":"09:05"},
               {"section":2,"start":"09:50","end":"09:10"},
               {"section":3,"start":"late","end":"10:00"}
             ]}
            """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertEquals(1, r.sectionTimes.size)
        assertEquals(3, r.warnings.count { it.startsWith("作息") })
    }

    @Test
    fun `script without section times leaves them empty`() {
        val json = """{"courses":[{"name":"高数","day":1,"startSection":1,"endSection":2}]}"""
        val r = JwScriptOutput.parse(json, 20) as JwScriptOutput.ParseResult.Ok
        assertTrue(r.sectionTimes.isEmpty())
    }
}
