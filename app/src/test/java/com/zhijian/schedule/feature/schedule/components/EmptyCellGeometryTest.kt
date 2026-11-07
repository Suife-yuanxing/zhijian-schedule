package com.zhijian.schedule.feature.schedule.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空课块 → 圆形扩散的生长原点。
 *
 * 这条几何错了不会崩，只表现为「圆从隔壁那格长出来」或「圆从一个 0×0 的点瞬间炸满」。
 * 两种都很难在真机逐帧里归因，所以在这里钉住。
 */
class EmptyCellGeometryTest {
    // 一列：左边 0，宽 158px；段高 125px，共 4 节
    private val column = Rect(left = 0f, top = 100f, right = 158f, bottom = 600f)
    private val section = 125f

    @Test
    fun `第 0 格贴着列顶`() {
        val cell = EmptyCellGeometry.cellOrColumn(column, section, 0, 4)
        assertEquals(0f, cell.left, 1e-4f)
        assertEquals(158f, cell.right, 1e-4f)
        assertEquals(100f, cell.top, 1e-4f)
        assertEquals(225f, cell.bottom, 1e-4f)
    }

    @Test
    fun `逐格下移 每格高度等于节高`() {
        for (i in 0..3) {
            val cell = EmptyCellGeometry.cellOrColumn(column, section, i, 4)
            assertEquals(100f + section * i, cell.top, 1e-4f)
            assertEquals(section, cell.height, 1e-4f)
        }
    }

    @Test
    fun `越界的下标被夹住 不会读到列外`() {
        val bottom = EmptyCellGeometry.cellOrColumn(column, section, 99, 4)
        assertTrue(bottom.bottom <= column.bottom + 1e-4f)
        assertEquals(column.bottom, bottom.bottom, 1e-4f)

        val top = EmptyCellGeometry.cellOrColumn(column, section, -5, 4)
        assertEquals(column.top, top.top, 1e-4f)
    }

    @Test
    fun `格子矩形始终落在列框内`() {
        for (i in -3..10) {
            val cell = EmptyCellGeometry.cellOrColumn(column, section, i, 4)
            assertTrue("i=$i top=${cell.top}", cell.top >= column.top - 1e-4f)
            assertTrue("i=$i bottom=${cell.bottom}", cell.bottom <= column.bottom + 1e-4f)
            assertTrue("i=$i 应有正高度", cell.height > 0f)
        }
    }

    @Test
    fun `列框还没测出来时退化`() {
        // Rect.Zero：调用方据此跳过扩散直接导航——从 0×0 的圆起手等于整屏瞬切
        assertEquals(Rect.Zero, EmptyCellGeometry.cellOrColumn(Rect.Zero, section, 0, 4))
        // 节高为 0（测量未完成）时也不能返回零面积矩形
        val fallback = EmptyCellGeometry.cellOrColumn(column, 0f, 0, 4)
        assertEquals(column, fallback)
    }
}
