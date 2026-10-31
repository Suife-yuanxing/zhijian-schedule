package com.zhijian.schedule.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekMaskTest {
    @Test
    fun `parse range expression`() {
        val mask = WeekMask.parseExpression(20, "1-16周")!!
        assertEquals(16, WeekMask.count(mask))
        assertEquals(20, mask.length)
        assertTrue(WeekMask.contains(mask, 16))
        assertFalse(WeekMask.contains(mask, 17))
    }

    @Test
    fun `parse odd weeks`() {
        val mask = WeekMask.parseExpression(20, "1-16周(单)")!!
        assertTrue(WeekMask.contains(mask, 1))
        assertFalse(WeekMask.contains(mask, 2))
        assertTrue(WeekMask.contains(mask, 15))
        assertEquals(8, WeekMask.count(mask))
    }

    @Test
    fun `parse bare parity`() {
        val mask = WeekMask.parseExpression(20, "双周")!!
        assertFalse(WeekMask.contains(mask, 1))
        assertTrue(WeekMask.contains(mask, 2))
        assertEquals(10, WeekMask.count(mask))
    }

    @Test
    fun `parse comma list`() {
        val mask = WeekMask.parseExpression(20, "1,3,5-9")!!
        assertEquals(listOf(1, 3, 5, 6, 7, 8, 9), WeekMask.weeks(mask))
    }

    @Test
    fun `normalize pads and truncates`() {
        assertEquals("1010", WeekMask.normalize("1010", 4))
        assertEquals("101000", WeekMask.normalize("1010", 6))
        assertEquals("10", WeekMask.normalize("1010", 2))
    }

    @Test
    fun `to expression compresses ranges`() {
        val mask = WeekMask.fromWeeks(20, listOf(1, 2, 3, 4, 6, 8, 9, 10))
        assertEquals("1-4,6,8-10周", WeekMask.toExpression(mask))
        assertEquals("全周", WeekMask.toExpression(WeekMask.of(20)))
    }

    @Test
    fun `describe empty mask as dash`() {
        assertEquals("—", WeekMask.describe("0000"))
    }

    @Test
    fun `describe full mask as whole week`() {
        assertEquals("全周", WeekMask.describe("1111"))
    }

    @Test
    fun `describe single week keeps expression form`() {
        assertEquals("3周", WeekMask.describe("00100"))
    }

    @Test
    fun `describe odd step2 weeks collapses to range with odd tag`() {
        val mask = WeekMask.parity(16, odd = true)
        assertEquals("1-15 单周", WeekMask.describe(mask))
    }

    @Test
    fun `describe even step2 weeks collapses to range with even tag`() {
        val mask = WeekMask.parity(16, odd = false)
        assertEquals("2-16 双周", WeekMask.describe(mask))
    }

    @Test
    fun `describe mixed weeks falls back to expression`() {
        assertEquals("1-3周", WeekMask.describe("11100"))
    }

    @Test
    fun `describe sparse odd weeks does not fake a range`() {
        // 1 和 5：间隔不是 2，不能写成「1-5 单周」
        val mask = WeekMask.fromWeeks(8, listOf(1, 5))
        assertEquals("1,5周", WeekMask.describe(mask))
    }
}
