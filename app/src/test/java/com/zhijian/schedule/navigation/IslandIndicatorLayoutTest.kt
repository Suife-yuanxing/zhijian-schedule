package com.zhijian.schedule.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.ui.island.ISLAND_PEAK_SPEED
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮岛指示器与底栏尺寸的纯几何。
 *
 * 钉死五条不变量：量化后与原始进度最多差 1/32、尺寸插值两端正好落在展开/收敛态、
 * 静止时胶囊正好铺满一格且左右各留 10dp、越界的 slide/速度都不会让胶囊跑出浮岛、
 * 底部留白按收敛进度插值且越界进度被夹住。
 *
 * 基准尺寸取真机三 tab：浮岛 324×64、格宽 108、左右内边距 10、上下 8 ⇒ 胶囊 88×48。
 */
class IslandIndicatorLayoutTest {

    private val barWidth = 324f
    private val barHeight = 64f
    private val itemWidth = 108f

    private fun pill(
        slide: Float,
        velocity: Float = 0f,
        travel: Float = 1f,
        width: Float = barWidth,
        height: Float = barHeight,
        item: Float = itemWidth,
    ): Rect = islandIndicatorRect(
        barWidthPx = width,
        barHeightPx = height,
        itemWidthPx = item,
        slide = slide,
        velocity = velocity,
        travel = travel,
        insetXPx = 10f,
        insetYPx = 8f,
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
        // 量化只是取整，不做夹紧：进度超过 1 时仍按原样外推（与改造前一致）
        assertEquals(1.0625f, quantizeCollapseProgress(1.05f), 1e-6f)
    }

    @Test
    fun `bar geometry interpolates between the expanded and collapsed states`() {
        // 展开态内缩更少、也更高；两端就是两个常量本身
        assertTrue(islandBarSideInset(1f) > islandBarSideInset(0f))
        assertTrue(islandBarHeight(1f) < islandBarHeight(0f))
        assertEquals(IslandInsetXExpanded, islandBarSideInset(0f))
        assertEquals(IslandInsetXCollapsed, islandBarSideInset(1f))
        assertEquals(IslandHeightExpanded, islandBarHeight(0f))
        assertEquals(IslandHeightCollapsed, islandBarHeight(1f))
        // 线性插值：中点等于两端均值
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
        // 三格总宽 264 与 324：同一格下标下，胶囊相对格左缘的偏移必须一样
        val narrow = pill(slide = 1f, width = 264f, item = 88f)
        val wide = pill(slide = 1f, width = 324f, item = 108f)
        assertEquals(10f, narrow.left - 88f, 1e-4f)
        assertEquals(narrow.left - 88f, wide.left - 108f, 1e-4f)
    }

    @Test
    fun `stretching widens and squashes the pill around its cell center`() {
        val rest = pill(slide = 1f)
        val stretched = pill(slide = 1f, velocity = ISLAND_PEAK_SPEED)
        assertTrue("w=${stretched.width}", stretched.width > rest.width)
        assertTrue("h=${stretched.height}", stretched.height < rest.height)
        // 高度变化上下对称：垂直中心不动
        assertEquals(rest.center.y, stretched.center.y, 1e-4f)
        // 前缘吃得多一些：形心朝行进方向略移（约 1.7dp 量级，远小于半格）
        assertTrue("dx=${stretched.center.x - rest.center.x}", stretched.center.x > rest.center.x)
        assertTrue(stretched.center.x - rest.center.x < itemWidth / 4f)
    }

    @Test
    fun `the leading edge takes more stretch in the direction of travel`() {
        // 基准取「静止胶囊」的两条边（1.5 倍格宽是网格中心线，不能当边缘用）
        val rest = pill(slide = 1f, velocity = 0f)
        val right = pill(slide = 1f, velocity = ISLAND_PEAK_SPEED)
        // 往右走：右缘是鼻尖，探出静止右缘的距离大于左缘拖后的距离
        val rightLead = right.right - rest.right
        val rightTrail = rest.left - right.left
        assertTrue("lead=$rightLead trail=$rightTrail", rightLead > rightTrail)

        // 往左走时镜像：多吃的是左缘，宽度不变
        val left = pill(slide = 1f, velocity = -ISLAND_PEAK_SPEED)
        val leftLead = rest.left - left.left
        val leftTrail = left.right - rest.right
        assertTrue("lead=$leftLead trail=$leftTrail", leftLead > leftTrail)
        assertEquals(right.width, left.width, 1e-4f)
    }

    @Test
    fun `a two cell jump does not sit at full stretch`() {
        val rest = pill(slide = 2f)
        val oneCell = pill(slide = 2f, velocity = ISLAND_PEAK_SPEED, travel = 1f)
        // 跨 2 格时峰速也是 2 倍，归一化基准跟着放大，同一速度只折半
        val twoCells = pill(slide = 2f, velocity = ISLAND_PEAK_SPEED, travel = 2f)
        assertTrue("two=${twoCells.width}", twoCells.width > rest.width)
        assertTrue("two=${twoCells.width} one=${oneCell.width}", twoCells.width < oneCell.width)
        assertEquals(oneCell.width - rest.width, 2f * (twoCells.width - rest.width), 1e-3f)
    }

    @Test
    fun `never escapes the island for any slide, velocity or overshoot`() {
        for (slide in listOf(-40f, -1f, 0f, 1f, 2f, 3f, 9f)) {
            for (velocity in listOf(-60f, -ISLAND_PEAK_SPEED, 0f, ISLAND_PEAK_SPEED, 60f)) {
                val r = pill(slide = slide, velocity = velocity)
                assertTrue("s=$slide v=$velocity left=${r.left}", r.left >= -1e-4f)
                assertTrue("s=$slide v=$velocity right=${r.right}", r.right <= barWidth + 1e-4f)
                assertTrue("s=$slide v=$velocity h=${r.height}", r.height <= barHeight + 1e-4f)
                assertTrue("s=$slide v=$velocity top=${r.top}", r.top >= -1e-4f)
                assertTrue("s=$slide v=$velocity w=${r.width}", r.width > 0f)
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
        // 越界进度被夹在两个端点之间（其他主题恒为 0，收敛动画也不会越过 0..1）
        assertEquals(IslandContentPadding, islandContentPadding(-0.5f))
        assertEquals(IslandContentPaddingCollapsed, islandContentPadding(1.5f))
    }
}
