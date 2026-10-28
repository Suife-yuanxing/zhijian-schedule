package com.zhijian.schedule.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 浮岛指示器与底栏尺寸的纯几何。
 *
 * 钉死五条不变量：量化后与原始进度最多差 1/32、尺寸插值两端正好落在展开/收敛态、
 * 静止时胶囊正好铺满一格且左右各留 10dp、行程中先拉长后回收且抵达即归位、
 * 越界的 slide / 行程都不会让胶囊跑出浮岛、底部留白按收敛进度插值且越界进度被夹住。
 *
 * 基准尺寸取真机三 tab：浮岛 324×64、格宽 108、左右内边距 10、上下 8 ⇒ 胶囊 88×48。
 */
class IslandIndicatorLayoutTest {
    private val barWidth = 324f
    private val barHeight = 64f
    private val itemWidth = 108f

    private fun pill(
        slide: Float,
        from: Float = slide,
        to: Float = slide,
        width: Float = barWidth,
        height: Float = barHeight,
        item: Float = itemWidth,
    ): Rect =
        islandIndicatorRect(
            metrics =
                IslandBarMetrics(
                    barWidthPx = width,
                    barHeightPx = height,
                    itemWidthPx = item,
                    insetXPx = 10f,
                    insetYPx = 8f,
                ),
            slide = slide,
            from = from,
            to = to,
        )

    @Test
    fun `quantized progress stays within one 32nd of the raw value`() {
        for (step in 0..64) {
            val raw = step / 64f
            assertTrue(
                "raw=$raw q=${quantizeCollapseProgress(raw)}",
                abs(quantizeCollapseProgress(raw) - raw) <= 1f / 32f + 1e-6f,
            )
        }
    }

    @Test
    fun `quantized progress snaps to the nearest 16th and keeps 0 and 1 exact`() {
        assertEquals(0f, quantizeCollapseProgress(0f), 1e-6f)
        assertEquals(1f, quantizeCollapseProgress(1f), 1e-6f)
        assertEquals(0.0625f, quantizeCollapseProgress(0.04f), 1e-6f)
        assertEquals(0.0625f, quantizeCollapseProgress(0.0625f), 1e-6f)
        assertEquals(0f, quantizeCollapseProgress(0.03f), 1e-6f)
        assertEquals(0.5f, quantizeCollapseProgress(0.48f), 1e-6f)
    }

    @Test
    fun `bar geometry interpolates between the expanded and collapsed states`() {
        assertTrue(islandBarSideInset(1f) > islandBarSideInset(0f))
        assertTrue(islandBarHeight(1f) < islandBarHeight(0f))
        assertEquals(IslandInsetXExpanded, islandBarSideInset(0f))
        assertEquals(IslandInsetXCollapsed, islandBarSideInset(1f))
        assertEquals(IslandHeightExpanded, islandBarHeight(0f))
        assertEquals(IslandHeightCollapsed, islandBarHeight(1f))
        assertEquals((IslandInsetXExpanded + IslandInsetXCollapsed) * 0.5f, islandBarSideInset(0.5f))
        assertEquals((IslandHeightExpanded + IslandHeightCollapsed) * 0.5f, islandBarHeight(0.5f))
    }

    @Test
    fun `at rest the pill fills one cell with a 10dp inset on both sides`() {
        for (index in 0..2) {
            val r = pill(slide = index.toFloat())
            assertEquals(10f, r.left - index * itemWidth, 1e-4f)
            assertEquals(itemWidth - 10f, r.right - index * itemWidth, 1e-4f)
            assertEquals(itemWidth - 20f, r.width, 1e-4f)
            assertEquals(barHeight - 16f, r.height, 1e-4f)
            assertEquals(barHeight / 2f, r.center.y, 1e-4f)
        }
    }

    @Test
    fun `pill position depends on the cell width, not on the bar width`() {
        val narrow = pill(slide = 1f, width = 264f, item = 88f)
        val wide = pill(slide = 1f, width = 324f, item = 108f)
        assertEquals(10f, narrow.left - 88f, 1e-4f)
        assertEquals(narrow.left - 88f, wide.left - 108f, 1e-4f)
    }

    @Test
    fun `jelly is widest in the first half of the trip and narrowest in the second half`() {
        val rest = pill(slide = 1f)
        val quarter = pill(slide = 1.25f, from = 1f, to = 2f)
        val threeQuarter = pill(slide = 1.75f, from = 1f, to = 2f)
        assertTrue("quarter w=${quarter.width}", quarter.width > rest.width + 8f)
        assertTrue("threeQuarter w=${threeQuarter.width}", threeQuarter.width < rest.width - 4f)
        val arrived = pill(slide = 2f, from = 1f, to = 2f)
        assertEquals(rest.width, arrived.width, 1e-4f)
        assertEquals(rest.height, arrived.height, 1e-4f)
    }

    @Test
    fun `leading edge is toward the travel direction`() {
        // 同一位移进度（0.25 = 出行相），只是方向相反：宽度一样、探出的边不一样
        val restAtOne = pill(slide = 1f)
        val restAtTwo = pill(slide = 2f)
        val rightward = pill(slide = 1.25f, from = 1f, to = 2f)
        val leftward = pill(slide = 1.75f, from = 2f, to = 1f)
        assertTrue(rightward.right - restAtOne.right > restAtOne.left - rightward.left)
        assertTrue(restAtTwo.left - leftward.left > leftward.right - restAtTwo.right)
        assertEquals(rightward.width, leftward.width, 1e-3f)
    }

    @Test
    fun `progress is clamped so overshoot cannot stretch beyond the trip`() {
        val beyond = pill(slide = 2.05f, from = 1f, to = 2f)
        val arrived = pill(slide = 2f, from = 1f, to = 2f)
        assertEquals(arrived.width, beyond.width, 1e-4f)
    }

    @Test
    fun `a trip with no span renders at rest`() {
        val r = pill(slide = 1f, from = 1f, to = 1f)
        assertEquals(itemWidth - 20f, r.width, 1e-4f)
        assertEquals(barHeight - 16f, r.height, 1e-4f)
    }

    @Test
    fun `never escapes the island for any slide or trip`() {
        for (slide in listOf(-40f, -1f, 0f, 1f, 1.3f, 2f, 3f, 9f)) {
            for (trip in listOf(-3f, -1f, 1f, 3f)) {
                val r = pill(slide = slide, from = 0f, to = trip)
                assertTrue("s=$slide t=$trip left=${r.left}", r.left >= -1e-4f)
                assertTrue("s=$slide t=$trip right=${r.right}", r.right <= barWidth + 1e-4f)
                assertTrue("s=$slide t=$trip h=${r.height}", r.height <= barHeight + 1e-4f)
                assertTrue("s=$slide t=$trip top=${r.top}", r.top >= -1e-4f)
                assertTrue("s=$slide t=$trip w=${r.width}", r.width > 0f)
            }
        }
    }

    @Test
    fun `collapsed bar keeps the pill inside the shorter bar`() {
        val r = pill(slide = 1f, width = 264f, height = 48f, item = 88f)
        assertEquals(32f, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
        assertEquals(24f, r.center.y, 1e-4f)
        assertTrue(r.right <= 264f + 1e-4f)
    }

    @Test
    fun `content padding interpolates and clamps the collapse progress`() {
        assertEquals(IslandContentPadding, islandContentPadding(0f))
        assertEquals(IslandContentPaddingCollapsed, islandContentPadding(1f))
        assertEquals((IslandContentPadding + IslandContentPaddingCollapsed) * 0.5f, islandContentPadding(0.5f))
        assertEquals(IslandContentPadding, islandContentPadding(-0.5f))
        assertEquals(IslandContentPaddingCollapsed, islandContentPadding(1.5f))
    }
}
