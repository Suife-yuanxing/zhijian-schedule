package com.zhijian.schedule.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次定位规则：只有锚点（课表 id / 开学日期 / 总周数）变化才重新定位。
 *
 * 这组断言守的就是主人报的「根页面切换时刷新位置错误」——翻到第 10 周、切走再切回，
 * 位置被副作用覆盖回第 9 周。
 */
class WeekAnchorPolicyTest {
    @Test
    fun firstCompositionRelocates() {
        assertTrue(WeekAnchorPolicy.shouldRelocate(previousAnchor = null, currentAnchor = "1|2026-08-31|20"))
    }

    @Test
    fun sameAnchorNeverRelocatesAgain() {
        val a = WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20)
        // 切 tab 回来 / 从编辑页返回 / Activity 重建：锚点没变，不许动页码
        assertFalse(WeekAnchorPolicy.shouldRelocate(previousAnchor = a, currentAnchor = a))
    }

    @Test
    fun changingTimetableRelocates() {
        val a = WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20)
        val b = WeekAnchorPolicy.anchorOf(2L, "2026-08-31", 20)
        assertTrue(WeekAnchorPolicy.shouldRelocate(a, b))
    }

    @Test
    fun changingStartDateRelocates() {
        val a = WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20)
        val b = WeekAnchorPolicy.anchorOf(1L, "2026-09-07", 20)
        assertTrue(WeekAnchorPolicy.shouldRelocate(a, b))
    }

    @Test
    fun changingTotalWeeksRelocates() {
        val a = WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20)
        val b = WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 18)
        assertTrue(WeekAnchorPolicy.shouldRelocate(a, b))
    }

    @Test
    fun noTimetableDoesNotRelocate() {
        assertFalse(WeekAnchorPolicy.shouldRelocate(previousAnchor = null, currentAnchor = null))
    }

    @Test
    fun targetPageIsZeroBasedCurrentWeek() {
        assertEquals(8, WeekAnchorPolicy.targetPage(currentWeek = 9, totalWeeks = 20))
        assertEquals(0, WeekAnchorPolicy.targetPage(currentWeek = 1, totalWeeks = 20))
    }

    @Test
    fun targetPageClampsIntoRange() {
        // 总周数被改小之后，自然当前周可能越界
        assertEquals(17, WeekAnchorPolicy.targetPage(currentWeek = 30, totalWeeks = 18))
        assertEquals(0, WeekAnchorPolicy.targetPage(currentWeek = null, totalWeeks = 20))
        assertEquals(0, WeekAnchorPolicy.targetPage(currentWeek = 5, totalWeeks = 0))
    }

    @Test
    fun anchorSignatureIsStableAndDistinct() {
        assertEquals(
            WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20),
            WeekAnchorPolicy.anchorOf(1L, "2026-08-31", 20),
        )
        assertEquals(null, WeekAnchorPolicy.anchorOf(null, "2026-08-31", 20))
    }
}
