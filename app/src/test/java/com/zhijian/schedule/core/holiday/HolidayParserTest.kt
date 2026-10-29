package com.zhijian.schedule.core.holiday

import com.zhijian.schedule.core.holiday.HolidayParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayParserTest {
    @Test
    fun parsesHolidayCnSample() {
        val json =
            """
            {"year":2026,"papers":["https://example.com"],"days":[
              {"name":"元旦","date":"2026-01-01","isOffDay":true},
              {"name":"元旦","date":"2026-01-02","isOffDay":true},
              {"name":"春节","date":"2026-02-08","isOffDay":false}
            ]}
            """.trimIndent()
        val items = HolidayParser.parseHolidayCn(json)
        assertEquals(3, items.size)
        assertEquals("2026-01-01", items[0].date)
        assertEquals("元旦", items[0].name)
        assertTrue(items[0].isOffDay)
        assertEquals(false, items[2].isOffDay)
    }

    @Test
    fun parsesTimorSample() {
        val json =
            """
            {"code":0,"holiday":{
              "01-01":{"holiday":true,"name":"元旦"},
              "02-08":{"holiday":false,"name":"春节调休","date":"2026-02-08"},
              "bad-key":{"holiday":true,"name":"X"}
            }}
            """.trimIndent()
        val items = HolidayParser.parseTimor(json, 2026)
        assertEquals(2, items.size)
        assertEquals("2026-01-01", items.first { it.name == "元旦" }.date)
        assertEquals("2026-02-08", items.first { !it.isOffDay }.date)
    }

    @Test
    fun timorErrorCodeYieldsEmpty() {
        assertEquals(0, HolidayParser.parseTimor("""{"code":-1}""", 2026).size)
    }

    @Test
    fun malformedJsonYieldsEmptyNotCrash() {
        assertTrue(HolidayParser.parseHolidayCn("<html>Just a moment...</html>").isEmpty())
        assertTrue(HolidayParser.parseTimor("not json at all", 2026).isEmpty())
    }
}
