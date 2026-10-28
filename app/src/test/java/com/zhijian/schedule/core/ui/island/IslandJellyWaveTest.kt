package com.zhijian.schedule.core.ui.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 果冻波形：**移动途中先拉长、然后回收**。
 *
 * 钉死六条不变量：两端为 0（起步与停稳都是原形）、前半程只有一个正峰、后半程只有一个负谷、
 * 拉得比收得重、两个峰都落在行程**之内**（不是停下来之后才晃）、几何上真的能窄过静止。
 *
 * 数值与 `docs/research/2026-10-28-jelly-capsule-motion.md` 的逐帧积分表一致：
 * 波形一改这里就该红——它同时是设计依据的回归锚点。
 */
class IslandJellyWaveTest {
    /** 出行峰的位置与高度：逐帧积分得 +1.0 @ 进度 0.25。 */
    private val leadPeakProgress = 0.25f

    /** 回收谷的位置与深度：−0.55 @ 进度 0.75。 */
    private val trailTroughProgress = 0.75f

    @Test
    fun `wave starts and ends at rest`() {
        assertEquals(0f, islandJellyWave(0f), 1e-6f)
        assertEquals(0f, islandJellyWave(1f), 1e-6f)
    }

    @Test
    fun `wave stays smooth at the seam between the two lobes`() {
        // 两瓣各占半程，接缝在进度 0.5；斜率必须连续，否则读作「拉伸突然换挡」
        val h = 1e-4f
        val left = (islandJellyWave(0.5f) - islandJellyWave(0.5f - h)) / h
        val right = (islandJellyWave(0.5f + h) - islandJellyWave(0.5f)) / h
        assertTrue("left=$left right=$right", abs(left - right) < 0.05f)
    }

    @Test
    fun `stretches before it retracts, both inside the trip`() {
        // 前半程是拉长（正），后半程是回收（负）——顺序与符号都不能反
        assertEquals(1f, islandJellyWave(leadPeakProgress), 2e-3f)
        assertTrue("at 0.1 ${islandJellyWave(0.1f)}", islandJellyWave(0.1f) > 0.3f)
        assertTrue("at 0.5 ${islandJellyWave(0.5f)}", abs(islandJellyWave(0.5f)) < 1e-3f)
        assertTrue("at 0.6 ${islandJellyWave(0.6f)}", islandJellyWave(0.6f) < -0.1f)
        assertTrue("at 0.75 ${islandJellyWave(trailTroughProgress)}", islandJellyWave(trailTroughProgress) < -0.5f)
        // 两个峰都在行程内部，抵达那一刻已经归位 ⇒ 不存在「停下来之后再晃」
        assertEquals(0f, islandJellyWave(1f), 1e-6f)
    }

    @Test
    fun `the lead stretch is heavier than the trail retraction`() {
        val lead = islandJellyWave(leadPeakProgress)
        val trail = islandJellyWave(trailTroughProgress)
        // 拉长是主戏、回收是余势：约 1 : 0.55
        assertTrue("lead=$lead trail=$trail", abs(lead) > abs(trail))
        assertTrue("ratio=${abs(lead / trail)}", abs(lead / trail) in 1.5f..2.2f)
    }

    @Test
    fun `progress outside zero to one is clamped`() {
        assertEquals(0f, islandJellyWave(-0.5f), 1e-6f)
        assertEquals(0f, islandJellyWave(1.5f), 1e-6f)
    }

    @Test
    fun `wave is used as a signed stretch so the pill can be narrower than at rest`() {
        assertTrue(islandJellyStretch(0.25f) > 0.9f)
        assertTrue(islandJellyStretch(0.75f) < 0f)
        assertEquals(0f, islandJellyStretch(1f), 1e-6f)
    }

    @Test
    fun `stretch phase widens and flattens the pill`() {
        val rest = rect(jelly = 0f)
        val stretched = rect(jelly = islandJellyStretch(leadPeakProgress))
        assertTrue("w=${stretched.width} rest=${rest.width}", stretched.width > rest.width + 8f)
        assertTrue("h=${stretched.height} rest=${rest.height}", stretched.height < rest.height - 2f)
        // 前缘多吃：形心朝行进方向前移，但远小于半格
        assertTrue(stretched.center.x > rest.center.x)
        assertTrue(stretched.center.x - rest.center.x < 88f / 4f)
    }

    @Test
    fun `retract phase narrows and lifts the pill`() {
        val rest = rect(jelly = 0f)
        val retracted = rect(jelly = islandJellyStretch(trailTroughProgress))
        assertTrue("w=${retracted.width} rest=${rest.width}", retracted.width < rest.width - 4f)
        assertTrue("h=${retracted.height} rest=${rest.height}", retracted.height > rest.height + 1f)
        // 垂直中心不动
        assertEquals(rest.center.y, retracted.center.y, 1e-4f)
    }

    @Test
    fun `direction mirrors the lead allocation and never escapes the island`() {
        for (step in 0..20) {
            for (dir in listOf(-1f, 0f, 1f)) {
                for (center in listOf(54f, 162f, 270f)) {
                    val r = rect(jelly = islandJellyStretch(step / 20f), direction = dir, centerX = center)
                    assertTrue("s=${step / 20f} d=$dir l=${r.left}", r.left >= -1e-4f)
                    assertTrue("s=${step / 20f} d=$dir r=${r.right}", r.right <= 324f + 1e-4f)
                    assertTrue("s=${step / 20f} d=$dir h=${r.height}", r.height <= 64f + 1e-4f)
                    assertTrue("s=${step / 20f} d=$dir w=${r.width}", r.width > 0f)
                }
            }
        }
    }

    /** 真机三 tab 基准：浮岛 324×64、胶囊静止 88×48、格中心 54。 */
    private fun rect(
        jelly: Float,
        centerX: Float = 54f,
        direction: Float = 1f,
    ) = islandPillRect(
        box =
            IslandPillBox(
                barWidth = 324f,
                barHeight = 64f,
                baseWidth = 88f,
                baseHeight = 48f,
            ),
        centerX = centerX,
        jelly = jelly,
        direction = direction,
    )
}
