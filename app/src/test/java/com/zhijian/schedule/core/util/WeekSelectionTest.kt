package com.zhijian.schedule.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 周次集合的结构识别与区间构造。
 *
 * 这几条是周次选择的真值层：界面上的"连续/单周/双周"三段、滑块的两个端点、
 * 逐周横条的每一格，最终都收敛到这里的三个函数。它们出错不会崩，
 * 只会安静地存错周次——所以每一条边界都要有断言。
 */
class WeekSelectionTest {
    @Test
    fun `empty set is EMPTY`() {
        assertEquals(WeekSelection.Mode.EMPTY, WeekSelection.mode(emptySet()))
    }

    @Test
    fun `single week counts as RANGE`() {
        // 单周可被"起点=终点"的区间表达，不必单列一态
        assertEquals(WeekSelection.Mode.RANGE, WeekSelection.mode(setOf(7)))
    }

    @Test
    fun `contiguous weeks are RANGE`() {
        assertEquals(WeekSelection.Mode.RANGE, WeekSelection.mode((3..9).toSet()))
    }

    @Test
    fun `odd weeks are ODD`() {
        assertEquals(WeekSelection.Mode.ODD, WeekSelection.mode(setOf(1, 3, 5, 7)))
    }

    @Test
    fun `even weeks are EVEN`() {
        assertEquals(WeekSelection.Mode.EVEN, WeekSelection.mode(setOf(2, 4, 6)))
    }

    @Test
    fun `sparse weeks are CUSTOM`() {
        // 3、7、11：等差 4，既不是区间也不是单双周
        assertEquals(WeekSelection.Mode.CUSTOM, WeekSelection.mode(setOf(3, 7, 11)))
    }

    @Test
    fun `mixed parity is CUSTOM`() {
        assertEquals(WeekSelection.Mode.CUSTOM, WeekSelection.mode(setOf(1, 2, 5)))
    }

    @Test
    fun `bounds of empty set is null`() {
        assertNull(WeekSelection.bounds(emptySet()))
    }

    @Test
    fun `bounds ignores order`() {
        assertEquals(4..9, WeekSelection.bounds(setOf(9, 4, 7)))
    }

    @Test
    fun `range builds inclusive set`() {
        assertEquals((5..8).toSet(), WeekSelection.range(5, 8, 20))
    }

    @Test
    fun `range normalizes reversed endpoints`() {
        // 滑块拖动时端点会短暂交叉，界面不该因此清空
        assertEquals((5..8).toSet(), WeekSelection.range(8, 5, 20))
    }

    @Test
    fun `range clamps to semester`() {
        assertEquals((18..20).toSet(), WeekSelection.range(18, 25, 20))
        assertEquals((1..2).toSet(), WeekSelection.range(-3, 2, 20))
    }

    @Test
    fun `parity keeps only odd weeks inside bounds`() {
        assertEquals(setOf(5, 7, 9), WeekSelection.parity(5..10, odd = true, totalWeeks = 20))
    }

    @Test
    fun `parity keeps only even weeks inside bounds`() {
        assertEquals(setOf(6, 8, 10), WeekSelection.parity(5..10, odd = false, totalWeeks = 20))
    }

    @Test
    fun `parity clamps upper bound`() {
        assertEquals(setOf(19), WeekSelection.parity(19..30, odd = true, totalWeeks = 20))
    }

    @Test
    fun `parity of out-of-range bounds is empty`() {
        assertEquals(emptySet<Int>(), WeekSelection.parity(25..30, odd = true, totalWeeks = 20))
    }
}
