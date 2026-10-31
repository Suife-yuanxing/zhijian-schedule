package com.zhijian.schedule.feature.schedule

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选周面板分行纯逻辑：4 列分行有序、月分界锚点、空/余数边界。 */
class WeekPickerRowsTest {
    private val start = LocalDate.parse("2026-08-31")

    @Test
    fun `chunks into four columns in order`() {
        val rows = WeekPickerRows.build(20, start)
        assertEquals(5, rows.size)
        assertEquals(listOf(1, 2, 3, 4), rows[0].weeks)
        assertEquals(listOf(17, 18, 19, 20), rows[4].weeks)
    }

    @Test
    fun `month labels anchor each new month at row start`() {
        val rows = WeekPickerRows.build(20, start)
        assertNull(rows[0].monthLabel)
        assertEquals(9, rows[1].monthLabel)
        assertEquals(10, rows[2].monthLabel)
        assertEquals(11, rows[3].monthLabel)
        assertEquals(12, rows[4].monthLabel)
    }

    @Test
    fun `no labels without start date or empty weeks`() {
        assertTrue(WeekPickerRows.build(20, null).all { it.monthLabel == null })
        assertTrue(WeekPickerRows.build(0, start).isEmpty())
    }

    @Test
    fun `remainder weeks land on last row`() {
        val rows = WeekPickerRows.build(18, start)
        assertEquals(listOf(17, 18), rows[4].weeks)
    }
}
