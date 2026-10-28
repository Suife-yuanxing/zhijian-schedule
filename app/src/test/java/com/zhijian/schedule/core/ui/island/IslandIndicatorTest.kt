package com.zhijian.schedule.core.ui.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 果冻胶囊的纯几何（不含波形）。
 *
 * 钉死五条不变量：静止时正好填满一格、正拉伸变宽变矮、负拉伸变窄变高、
 * 前缘比后缘吃得多（含回收时前缘先收）、任何方向与过冲下都不越出浮岛。
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
        jelly: Float,
        direction: Float = 0f,
        centerX: Float = cellCenter,
    ) = islandPillRect(
        box = IslandPillBox(barWidth, barHeight, baseWidth, baseHeight),
        centerX = centerX,
        jelly = jelly,
        direction = direction,
    )

    @Test
    fun `at rest the pill fills one cell with 10dp side inset`() {
        val r = pill(jelly = 0f)
        assertEquals(10f, r.left, 1e-4f)
        assertEquals(98f, r.right, 1e-4f)
        assertEquals(baseWidth, r.width, 1e-4f)
        assertEquals(baseHeight, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
    }

    @Test
    fun `positive jelly widens and flattens the pill`() {
        val r = pill(jelly = 1f, direction = 1f)
        assertEquals(baseWidth * (1f + IslandTokens.PILL_GROW), r.width, 1e-4f)
        assertEquals(baseHeight * (1f - IslandTokens.PILL_SQUASH), r.height, 1e-4f)
        assertTrue(r.width > baseWidth)
        assertTrue(r.height < baseHeight)
    }

    @Test
    fun `negative jelly narrows and lifts the pill`() {
        // 回收相：横着收窄、竖着鼓起（体积守恒的另一半）
        val r = pill(jelly = -0.55f)
        assertTrue("w=${r.width}", r.width < baseWidth - 4f)
        assertTrue("h=${r.height}", r.height > baseHeight + 1f)
        assertEquals(barHeight / 2f, r.center.y, 1e-4f)
    }

    @Test
    fun `the leading edge takes more stretch than the trailing edge`() {
        val right = pill(jelly = 1f, direction = 1f)
        val rightLead = right.right - (cellCenter + baseWidth / 2f)
        val rightTrail = (cellCenter - baseWidth / 2f) - right.left
        assertTrue("lead=$rightLead trail=$rightTrail", rightLead > rightTrail)

        // 往左走时镜像：多吃的是左缘
        val left = pill(jelly = 1f, direction = -1f)
        val leftLead = (cellCenter - baseWidth / 2f) - left.left
        val leftTrail = left.right - (cellCenter + baseWidth / 2f)
        assertTrue("lead=$leftLead trail=$leftTrail", leftLead > leftTrail)
        assertEquals(right.width, left.width, 1e-4f)
    }

    @Test
    fun `during retraction the leading edge pulls in first`() {
        // 负值沿用同一条非对称分配 ⇒ 前缘先收回来，读作「到站刹住」
        val restRight = cellCenter + baseWidth / 2f
        val r = pill(jelly = -0.55f, direction = 1f)
        val leadPull = restRight - r.right
        val trailPull = r.left - (cellCenter - baseWidth / 2f)
        assertTrue("lead=$leadPull trail=$trailPull", leadPull > trailPull)
    }

    @Test
    fun `jelly stays vertically centered`() {
        for (step in -10..10) {
            val r = pill(jelly = step / 10f)
            assertEquals(barHeight / 2f, r.center.y, 1e-4f)
        }
    }

    @Test
    fun `never escapes the island for any jelly, direction or overshoot`() {
        for (center in listOf(-40f, 0f, 54f, 162f, 270f, 324f, 380f)) {
            for (step in -12..12) {
                for (dir in listOf(-1f, 0f, 1f)) {
                    val r = pill(jelly = step / 10f, direction = dir, centerX = center)
                    assertTrue("c=$center s=${step / 10f} left=${r.left}", r.left >= -1e-4f)
                    assertTrue("c=$center s=${step / 10f} right=${r.right}", r.right <= barWidth + 1e-4f)
                    assertTrue("c=$center h=${r.height}", r.height <= barHeight + 1e-4f)
                    assertTrue("c=$center w=${r.width}", r.width > 0f)
                }
            }
        }
    }

    @Test
    fun `pill height never exceeds the bar even when the bar collapses`() {
        // 收敛态浮岛只剩 48dp 高，胶囊要跟着矮下去而不是把标签顶出去
        val collapsed =
            IslandPillBox(barWidth = 264f, barHeight = 48f, baseWidth = 68f, baseHeight = 32f)
        val r =
            islandPillRect(
                box = collapsed,
                centerX = 44f,
                jelly = 0f,
                direction = 0f,
            )
        assertEquals(32f, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
    }
}
