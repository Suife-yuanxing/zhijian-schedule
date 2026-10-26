package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.feature.importer.parsers.CsvParser
import com.zhijian.schedule.feature.importer.parsers.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableMappingTest {

    @Test
    fun `csv with header maps correctly`() {
        val csv = """
            课程名称,星期,开始节,结束节,老师,地点,周数
            大学英语,周一,1,2,李四,教101,1-16周
            体育,周五,3,4,王五,体育馆,1-16周(双)
        """.trimIndent()
        val result = CsvParser.parse(csv, 20)
        assertEquals(2, result.drafts.size)
        val english = result.drafts[0]
        assertEquals("大学英语", english.name)
        assertEquals(1, english.dayOfWeek)
        assertEquals(2, english.endSection)
        assertEquals("教101", english.room)
        val pe = result.drafts[1]
        assertEquals(5, pe.dayOfWeek)
        assertEquals(8, pe.weekMask.count { it == '1' })
    }

    @Test
    fun `tab separated without header`() {
        val text = "线性代数\t周三\t5\t6\t赵六\t理A402\t1-8周"
        val result = CsvParser.parse(text, 20)
        assertEquals(1, result.drafts.size)
        assertEquals(3, result.drafts[0].dayOfWeek)
        assertEquals(5, result.drafts[0].startSection)
    }

    @Test
    fun `json course array`() {
        val json = """
            {"courses":[
              {"name":"高等数学","teacher":"王五","room":"理A401","dayOfWeek":2,
               "startSection":3,"endSection":4,"weekMask":"11111111111111110000"}
            ]}
        """.trimIndent()
        val result = JsonParser.parse(json, 20)
        assertEquals(1, result.drafts.size)
        val math = result.drafts[0]
        assertEquals("高等数学", math.name)
        assertEquals(2, math.dayOfWeek)
        assertEquals(16, math.weekMask.count { it == '1' })
    }

    @Test
    fun `wakeup-like chinese keys json`() {
        val json = """
            [{"课程名":"程序设计基础","星期":"周四","开始节":7,"结束节":8,"老师":"孙八","地点":"实验楼A401","周次":"1-16周"}]
        """.trimIndent()
        val result = JsonParser.parse(json, 20)
        assertEquals(1, result.drafts.size)
        assertEquals(4, result.drafts[0].dayOfWeek)
        assertTrue(result.drafts[0].room.contains("A401"))
    }
}
