package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩散缓动的性格与分发。
 *
 * 正向（页面扩散、换主题共用）必须是「慢起快收」的加速型 cubic-bezier(0.5, 0, 1, 1)，
 * 反向必须是它的严格时间镜像 cubic-bezier(0, 0, 0.5, 1)。
 *
 * 数值是三次贝塞尔的解析解，当回归闸门用——曲线一改，这里必须跟着改，
 * 免得有人顺手把页面扩散换回旧的前重后轻曲线，或者让反向和正向「曲线打架」而没人发现。
 *
 * 2026-11-04 这一版把 x1 从 0.3 推到 0.5：需求要的是「由慢到快」，同族里前段最空的一条。
 * 代价（起步 100ms 只走 5% 面积）由 DUR_REVEAL 450→400 补上；反向随之变成 (0,0,0.5,1)。
 */
class RevealEasingTest {
    private val accel = Motion.EaseRevealAccelerate
    private val mirror = Motion.EaseRevealDecelerate

    @Test
    fun `两端固定 0 进 0 出 1 进 1 出`() {
        assertEquals(0f, accel.transform(0f), 1e-6f)
        assertEquals(1f, accel.transform(1f), 1e-6f)
        assertEquals(0f, mirror.transform(0f), 1e-6f)
        assertEquals(1f, mirror.transform(1f), 1e-6f)
    }

    @Test
    fun `单调不减 圆只能往外长`() {
        var prevAccel = -1f
        var prevMirror = -1f
        var i = 0
        while (i <= 100) {
            val x = i / 100f
            val y = accel.transform(x)
            val m = mirror.transform(x)
            assertTrue("x=$x 的行程 $y 不该小于 $prevAccel", y >= prevAccel - 1e-6f)
            assertTrue("x=$x 的镜像行程 $m 不该小于 $prevMirror", m >= prevMirror - 1e-6f)
            prevAccel = y
            prevMirror = m
            i++
        }
    }

    @Test
    fun `末端速度高于起步 这就是加速型`() {
        // 首 10% 时间走的行程 vs 末 10% 时间走的行程
        val head = accel.transform(0.1f) - accel.transform(0f)
        val tail = accel.transform(1f) - accel.transform(0.9f)
        assertTrue("起步段 $head 必须明显小于收尾段 $tail", tail > head * 2f)
    }

    @Test
    fun `同族里选的是前段最空的一条 这就是由慢到快`() {
        // 25% / 50% / 75% 时间点的半径行程（解析值）
        assertEquals(0.075f, accel.transform(0.25f), 0.005f)
        assertEquals(0.278f, accel.transform(0.5f), 0.005f)
        assertEquals(0.586f, accel.transform(0.75f), 0.005f)
        // 同族的其他加速型都比它前段更满：x1 越小、前段越快。
        // 选 0.5 而不是 0.3 就是需求里的「由慢到快」——前段几乎不动，末段一口气铺满。
        for (x1 in listOf(0.2f, 0.3f, 0.4f)) {
            val candidate = CubicBezierEasing(x1, 0f, 1f, 1f)
            assertTrue(
                "x1=$x1 的半程行程 ${candidate.transform(0.5f)} 应比 0.5 那条更满",
                candidate.transform(0.5f) > accel.transform(0.5f),
            )
        }
        // M3 emphasized.accelerate 更极端（半程 15.5%），但它的末段几乎不收尾
        // （终点切线斜率 0.15/0.7 ≈ 0.2），整屏大半时间纹丝不动，读作卡顿
        val emphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        assertTrue(emphasizedAccel.transform(0.5f) < 0.2f)
        // 页面扩散不用前重后轻的 (0.4, 0, 0.2, 1)：它半程已走完 77.6%，
        // 与「先慢后快、末端才咬到最远角」的加速型正好相反，混用会让进出两条曲线打架
        val oldPageCurve = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        assertTrue(oldPageCurve.transform(0.5f) > 0.7f)
        assertTrue(oldPageCurve.transform(0.5f) > accel.transform(0.5f) * 2f)
    }

    @Test
    fun `末段速比被拉到旧版的三倍`() {
        // 「由慢到快」的强度用「末 10% 行程 / 首 10% 行程」度量：
        // 旧曲线 (0.3,0,1,1) 是 4.99×，新曲线 (0.5,0,1,1) 是 14.10×。
        // 这条断言守住的是「有没有被后人顺手调回温和版」，不是某个具体数值。
        val head = accel.transform(0.1f) - accel.transform(0f)
        val tail = accel.transform(1f) - accel.transform(0.9f)
        assertEquals(1.28f, head * 100f, 0.1f)
        assertEquals(18.02f, tail * 100f, 0.1f)
        assertTrue("末段/首段速比应 ≥ 12（旧版只有 5）", tail / head >= 12f)
        // 也不能无限推：x1 = 0.6 时半程只有 23.4%，首 100ms 屏幕几乎不动，读作「点了没反应」
        val tooFar = CubicBezierEasing(0.6f, 0f, 1f, 1f)
        assertTrue(tooFar.transform(0.5f) < 0.24f)
        assertTrue(tooFar.transform(0.5f) < accel.transform(0.5f))
    }

    @Test
    fun `反向曲线是正向曲线的严格时间镜像`() {
        // 倒放恒等式：把 (x1, y1, x2, y2) 按时间倒放得到 (1-x2, 1-y2, 1-x1, 1-y1)，
        // 即 mirror(t) == 1 - accel(1 - t)。这条成立，反向才真正是正向的倒放。
        var i = 0
        while (i <= 100) {
            val t = i / 100f
            assertEquals(
                "t=$t 处镜像应等于倒放",
                1f - accel.transform(1f - t),
                mirror.transform(t),
                1e-5f,
            )
            i++
        }
        // 镜像的解析值（cubic-bezier(0, 0, 0.5, 1)）
        assertEquals(0.1802f, mirror.transform(0.1f), 0.005f)
        assertEquals(0.7219f, mirror.transform(0.5f), 0.005f)
        assertEquals(0.9246f, mirror.transform(0.75f), 0.005f)
    }

    @Test
    fun `反向起步快 正向起步慢`() {
        // 正向 10% 时间几乎不动（静止起步），反向 10% 时间已经收掉近两成——按下立刻有反馈
        assertTrue(accel.transform(0.1f) < 0.02f)
        assertTrue("反向 10% 时间应已收掉超过 17%", mirror.transform(0.1f) > 0.17f)
        assertTrue(mirror.transform(0.1f) > accel.transform(0.1f) * 4f)
    }

    @Test
    fun `时长补偿把加速型的滞后补回同一档`() {
        // 「读作铺满」= 半径走到 77.9%。新曲线要到 87.55% 的时长才走到同一个半径，
        // 所以时长必须跟着往下压（450 → 400），否则这一步从 377ms 拖到 450 × 0.8755 ≈ 394ms。
        val arriveAtFraction = 0.8755f
        assertEquals(0.779f, accel.transform(arriveAtFraction), 0.003f)
        for (duration in listOf(Motion.DUR_REVEAL, Motion.DUR_REVEAL_THEME)) {
            val arriveAt = duration * arriveAtFraction
            assertTrue("时长 $duration 下这一步落在 ${arriveAt}ms，太晚了", arriveAt - 280f < 150f)
        }
        // 页面扩散这次是**主动压短**到 400：读作铺满落在 350ms，比旧版的 377ms 还早，
        // 起点段的「静」由曲线给出，不再靠「整段都慢」来伪装从容。
        assertEquals(350f, Motion.DUR_REVEAL * arriveAtFraction, 2f)
        // 换皮肤没有「点下去立刻要应答」的诉求，仍守 long1 的档位，两条时间线从此各自演进
        assertTrue(Motion.DUR_REVEAL_THEME > Motion.DUR_REVEAL)
        // 反向用镜像曲线（前重后轻），不需要时长补偿；但必须比正向短，保持「收回比张开短一截」
        assertTrue(Motion.DUR_REVEAL_BACK < Motion.DUR_REVEAL)
    }

    @Test
    fun `时间线分发 页面正向与换主题共用加速曲线 反向走镜像`() {
        val forward = revealTiming(reverse = false, themeSwitch = false)
        assertEquals(Motion.DUR_REVEAL, forward.durationMillis)
        assertSame(Motion.EaseRevealAccelerate, forward.easing)

        val theme = revealTiming(reverse = false, themeSwitch = true)
        assertEquals(Motion.DUR_REVEAL_THEME, theme.durationMillis)
        assertSame(Motion.EaseRevealAccelerate, theme.easing)

        val back = revealTiming(reverse = true, themeSwitch = false)
        assertEquals(Motion.DUR_REVEAL_BACK, back.durationMillis)
        assertSame(Motion.EaseRevealDecelerate, back.easing)

        // 反向优先：requestNavigationBack 的 toId 恒为 null，两者同时为真不可达，
        // 这条优先级只是把「不可达组合」的语义提前定死
        assertSame(Motion.EaseRevealDecelerate, revealTiming(reverse = true, themeSwitch = true).easing)
    }
}
