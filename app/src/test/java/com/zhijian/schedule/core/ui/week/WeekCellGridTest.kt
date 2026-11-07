package com.zhijian.schedule.core.ui.week

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WeekCellGrid 轴向判定的边界：拖选认横向、滚动交还纵向。
 * 这里是旧格阵「页面滚不动」缺陷的回归防线——纵向占优与平局都必须不抢手势。
 */
class WeekCellGridTest {
    private val slop = 8f

    @Test
    fun `below touch slop no axis is decided`() {
        assertEquals(DragAxis.NONE, resolveDragAxis(dx = 4f, dy = 3f, touchSlop = slop))
    }

    @Test
    fun `exact touch slop decides immediately`() {
        assertEquals(DragAxis.HORIZONTAL, resolveDragAxis(dx = 8f, dy = 0f, touchSlop = slop))
    }

    @Test
    fun `horizontal dominant becomes drag select`() {
        assertEquals(DragAxis.HORIZONTAL, resolveDragAxis(dx = 30f, dy = 5f, touchSlop = slop))
    }

    @Test
    fun `vertical dominant is handed back to parent scroll`() {
        assertEquals(DragAxis.VERTICAL, resolveDragAxis(dx = 5f, dy = 30f, touchSlop = slop))
    }

    @Test
    fun `tie falls back to vertical as the safe direction`() {
        assertEquals(DragAxis.VERTICAL, resolveDragAxis(dx = 20f, dy = 20f, touchSlop = slop))
    }
}
