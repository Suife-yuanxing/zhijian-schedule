package com.zhijian.schedule.core.ui.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：浮岛收起后横向翻周，底栏不该自己弹开。
 *
 * 根因是「每一周的 grid 都 remember 一份新的 ScrollState」，新页首次组合时
 * 滚动位置天然为 0。旧实现把 atTop==true 直接当成「用户滚回顶部」，
 * 于是翻页即展开。这里把判定逻辑从 Compose 里剥出来单测。
 */
class IslandTopEdgeTrackerTest {

    @Test
    fun `首次激活就已在顶部不算回到顶部`() {
        val tracker = IslandTopEdgeTracker()
        // 新页首次组合：位置为 0
        assertFalse(
            "翻到新的一周时不能把浮岛顶开",
            tracker.observe(atTop = true),
        )
        // 再报一次仍然在顶部，也不该算边沿
        assertFalse(tracker.observe(atTop = true))
    }

    @Test
    fun `先离开顶部再回到顶部才算回到顶部`() {
        val tracker = IslandTopEdgeTracker()
        assertFalse(tracker.observe(atTop = true))   // 初始基线
        assertFalse(tracker.observe(atTop = false))  // 往下滚
        assertTrue(tracker.observe(atTop = true))    // 滚回顶部 → 展开
    }

    @Test
    fun `滚回顶部只触发一次`() {
        val tracker = IslandTopEdgeTracker()
        tracker.observe(atTop = true)
        tracker.observe(atTop = false)
        assertTrue(tracker.observe(atTop = true))
        // 停在顶部不应该重复触发
        assertFalse(tracker.observe(atTop = true))
        assertFalse(tracker.observe(atTop = true))
    }

    @Test
    fun `连续翻周不会累积出一次假回顶`() {
        // 每一页各自一份 tracker，模拟翻过 3 页
        val pages = List(3) { IslandTopEdgeTracker() }
        val expanded = pages.map { it.observe(atTop = true) }
        assertTrue("任何一页首次组合都不该展开浮岛", expanded.none { it })
    }
}
