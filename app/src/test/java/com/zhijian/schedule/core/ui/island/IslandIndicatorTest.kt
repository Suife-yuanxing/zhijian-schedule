package com.zhijian.schedule.core.ui.island

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮岛选中胶囊的果冻几何。
 *
 * 钉死五条不变量：静止时正好填满一格、拉伸时变宽变矮、前缘比后缘吃得多、
 * 任何速度 / 方向 / 过冲下都不越出浮岛、速度归一化与跨度无关。
 *
 * 基准尺寸取真机三 tab：浮岛 324×64、格宽 108、左右内边距 10、上下 8
 * ⇒ 胶囊静止 88×48。
 */
class IslandIndicatorTest {

    private val barWidth = 324f
    private val barHeight = 64f
    private val baseWidth = 88f
    private val baseHeight = 48f
    private val cellCenter = 54f

    private fun pill(
        stretch: Float,
        direction: Float = 0f,
        centerX: Float = cellCenter,
    ) = islandPillRect(
        barWidth = barWidth,
        barHeight = barHeight,
        baseWidth = baseWidth,
        baseHeight = baseHeight,
        centerX = centerX,
        stretch = stretch,
        direction = direction,
    )

    @Test
    fun `at rest the pill fills one cell with 10dp side inset`() {
        val r = pill(stretch = 0f)
        assertEquals(10f, r.left, 1e-4f)
        assertEquals(98f, r.right, 1e-4f)
        assertEquals(baseWidth, r.width, 1e-4f)
        assertEquals(baseHeight, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
    }

    @Test
    fun `stretching makes the pill wider and shorter`() {
        val r = pill(stretch = 1f, direction = 1f)
        assertEquals(baseWidth * (1f + IslandTokens.PILL_GROW), r.width, 1e-4f)
        assertEquals(baseHeight * (1f - IslandTokens.PILL_SQUASH), r.height, 1e-4f)
        assertTrue(r.width > baseWidth)
        assertTrue(r.height < baseHeight)
    }

    @Test
    fun `the leading edge takes more stretch than the trailing edge`() {
        val right = pill(stretch = 1f, direction = 1f)
        val rightLead = right.right - (cellCenter + baseWidth / 2f)
        val rightTrail = (cellCenter - baseWidth / 2f) - right.left
        assertTrue("lead=$rightLead trail=$rightTrail", rightLead > rightTrail)

        // 往左走时镜像：多吃的是左缘
        val left = pill(stretch = 1f, direction = -1f)
        val leftLead = (cellCenter - baseWidth / 2f) - left.left
        val leftTrail = left.right - (cellCenter + baseWidth / 2f)
        assertTrue("lead=$leftLead trail=$leftTrail", leftLead > leftTrail)
        assertEquals(right.width, left.width, 1e-4f)
    }

    @Test
    fun `stretch stays vertically centered`() {
        for (step in 0..10) {
            val r = pill(stretch = step / 10f)
            assertEquals(barHeight / 2f, r.center.y, 1e-4f)
        }
    }

    @Test
    fun `never escapes the island for any stretch, direction or overshoot`() {
        for (center in listOf(-40f, 0f, 54f, 162f, 270f, 324f, 380f)) {
            for (step in 0..10) {
                for (dir in listOf(-1f, 0f, 1f)) {
                    val r = pill(stretch = step / 10f, direction = dir, centerX = center)
                    assertTrue("c=$center s=${step / 10f} left=${r.left}", r.left >= -1e-4f)
                    assertTrue("c=$center s=${step / 10f} right=${r.right}", r.right <= barWidth + 1e-4f)
                    assertTrue("c=$center h=${r.height}", r.height <= barHeight + 1e-4f)
                    assertTrue("c=$center w=${r.width}", r.width > 0f)
                }
            }
        }
    }

    @Test
    fun `stretch is zero at rest and saturates at the single-step peak speed`() {
        assertEquals(0f, islandStretch(0f), 1e-4f)
        assertEquals(1f, islandStretch(ISLAND_PEAK_SPEED), 1e-4f)
        assertEquals(1f, islandStretch(60f), 1e-4f)
        assertTrue(islandStretch(ISLAND_PEAK_SPEED / 2f) > 0.49f)
    }

    @Test
    fun `stretch ignores travel direction`() {
        assertEquals(islandStretch(6f), islandStretch(-6f), 1e-6f)
    }

    @Test
    fun `a two cell jump does not sit at full stretch`() {
        // 跨 2 格时峰速也是 2 倍，归一化基准跟着放大，同一速度只折半
        assertEquals(0.5f, islandStretch(ISLAND_PEAK_SPEED, travel = 2f), 1e-4f)
        // travel 为 0（静止 / 首帧）时退化成单步基准，不会除零
        assertEquals(0f, islandStretch(0f, travel = 0f), 1e-4f)
    }

    @Test
    fun `pill height never exceeds the bar even when the bar collapses`() {
        // 收敛态浮岛只剩 48dp 高，胶囊要跟着矮下去而不是把标签顶出去
        val r = islandPillRect(
            barWidth = 264f,
            barHeight = 48f,
            baseWidth = 68f,
            baseHeight = 32f,
            centerX = 44f,
            stretch = 0f,
            direction = 0f,
        )
        assertEquals(32f, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
    }
}
