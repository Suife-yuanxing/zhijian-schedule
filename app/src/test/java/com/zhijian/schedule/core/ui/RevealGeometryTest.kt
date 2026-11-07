package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 扩散边界的两套口径：换主题走圆（必须覆盖到屏幕最远角），
 * 页面扩散走种子轮廓（外扩距离必须刚好覆盖整屏，且起手就是控件本身）。
 */
class RevealGeometryTest {
    @Test
    fun centerOfScreenCoversHalfDiagonal() {
        assertEquals(hypot(100f, 200f), revealRadius(100f, 200f, 200f, 400f), 1e-3f)
    }

    @Test
    fun topLeftCornerCoversFullDiagonal() {
        assertEquals(hypot(1080f, 2376f), revealRadius(0f, 0f, 1080f, 2376f), 1e-3f)
    }

    @Test
    fun centerNearOneEdgeStillReachesOppositeCorner() {
        // 圆心贴着右边：最容易出错的是「只量到近边」，那样左侧会盖不满
        assertEquals(hypot(1080f, 1900f), revealRadius(1080f, 100f, 1080f, 2000f), 1e-3f)
    }

    @Test
    fun radiusReachesEveryCornerForAnyOrigin() {
        val w = 1080f
        val h = 2376f
        for (x in listOf(0f, 137f, 540f, 1080f)) {
            for (y in listOf(0f, 233f, 1188f, 2376f)) {
                val radius = revealRadius(x, y, w, h)
                for (cx in listOf(0f, w)) {
                    for (cy in listOf(0f, h)) {
                        assertTrue(
                            "corner must be covered by radius",
                            hypot(cx - x, cy - y) <= radius + 1e-3f,
                        )
                    }
                }
            }
        }
    }

    /**
     * 反向（返回）的外扩必须从「盖满整屏」单调收到 0（正好是控件本身）。
     *
     * 反向曾经叠过一层整块 zoom + fade 想加强存在感，结果边界被缩一遍、图又被缩一遍，
     * 画出来的残影既不在按钮中心、边界也不是控件形状。这条测试把「只收边界」钉死。
     */
    @Test
    fun reverseDilationIsMonotonicFromFullToZero() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        // 加号真实位置：48dp @3x，右下角
        val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)
        val seed = SeedShape(fab, 48f)
        assertTrue(s.requestNavigationBack(seed, Color.Black) { })
        s.beginReveal(null)
        val active = s.active!!
        val far = dilationToCover(seed, 1080f, 2376f)

        // t=0：起手就得盖满，否则返程第一帧四角会先漏出课表
        s.progress.floatValue = 0f
        assertEquals(far, s.dilationOf(active), 1e-2f)
        // t=1：收成控件那么大，剩下的交给真实的加号接手
        s.progress.floatValue = 1f
        assertEquals(0f, s.dilationOf(active), 1e-2f)

        var prev = Float.MAX_VALUE
        for (i in 0..20) {
            s.progress.floatValue = i / 20f
            val d = s.dilationOf(active)
            assertTrue("dilation must not increase", d <= prev + 1e-3f)
            assertTrue("dilation must not exceed full cover", d <= far + 1e-3f)
            assertTrue("dilation must not be negative", d >= -1e-3f)
            prev = d
        }
    }

    /** 同一进度下正向与反向互为镜像：fwd(t) + rev(t) == 满屏外扩距离 */
    @Test
    fun forwardAndReverseMirrorEachOther() {
        val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)
        val seed = SeedShape(fab, 48f)

        val fwd = RevealState()
        fwd.hostSize = IntSize(1080, 2376)
        fwd.requestNavigation(seed, Color.Black) { }
        fwd.beginReveal(null)
        val a1 = fwd.active!!

        val rev = RevealState()
        rev.hostSize = IntSize(1080, 2376)
        rev.requestNavigationBack(seed, Color.Black) { }
        rev.beginReveal(null)
        val a2 = rev.active!!

        assertEquals(a1.shape, a2.shape)

        val far = dilationToCover(seed, 1080f, 2376f)
        for (i in 0..10) {
            val t = i / 10f
            fwd.progress.floatValue = t
            rev.progress.floatValue = t
            assertEquals(far, fwd.dilationOf(a1) + rev.dilationOf(a2), 1e-2f)
        }
    }

    /**
     * 外扩距离必须刚好覆盖整屏：少一像素，最后一帧就会在屏幕边缘留一条旧页的线。
     *
     * 这是「精准测量边缘」的可执行口径——四条边各自到画布的距离不一样，取最远的那个。
     */
    @Test
    fun dilationCoversAllFourEdgesAtMinimumCost() {
        val seed = SeedShape(Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f), 48f)
        val g = dilationToCover(seed, 1080f, 2376f)
        // 四条边各自所需：左 876、右 60、上 1896、下 336，取最大 1896（+2 抗锯齿留边）
        assertEquals(1898f, g, 1e-3f)
        val dilated = dilatedRound(seed, g)
        assertTrue("left must cross the canvas edge", dilated.left <= 0f)
        assertTrue("top must cross the canvas edge", dilated.top <= 0f)
        assertTrue("right must cross the canvas edge", dilated.right >= 1080f)
        assertTrue("bottom must cross the canvas edge", dilated.bottom >= 2376f)
    }

    /**
     * 角半径必须随外扩同步增长：否则形状会在中途从「圆角方块」变成「方角方块」。
     *
     * 这是「页面沿着整个加号方块展开」的形状契约——边界始终是种子轮廓的等距外扩，
     * 而不是一个把它包住的圆。
     */
    @Test
    fun cornerRadiusGrowsWithDilation() {
        val seed = SeedShape(Rect(0f, 0f, 100f, 100f), 16f)
        assertEquals(16f, dilatedRound(seed, 0f).topLeftCornerRadius.x, 1e-3f)
        assertEquals(66f, dilatedRound(seed, 50f).topLeftCornerRadius.x, 1e-3f)
        assertEquals(116f, dilatedRound(seed, 100f).topLeftCornerRadius.x, 1e-3f)
    }

    /** 外扩 0 时轮廓**就是**控件本身：这是「页面从方块里长出来」的几何定义 */
    @Test
    fun zeroDilationEqualsTheWidgetItself() {
        val seed = SeedShape(Rect(876f, 1896f, 1020f, 2040f), 48f)
        val round = dilatedRound(seed, 0f)
        assertEquals(876f, round.left, 1e-3f)
        assertEquals(1896f, round.top, 1e-3f)
        assertEquals(1020f, round.right, 1e-3f)
        assertEquals(2040f, round.bottom, 1e-3f)
        assertEquals(48f, round.topLeftCornerRadius.x, 1e-3f)
    }

    /**
     * 方块退场第一帧**就是**它自己：与 `dilatedRound(seed, 0f)` 逐字段相等。
     *
     * 这是「退场与展开同一套语言」的接缝条件——若退场从「已经内缩一点」的轮廓起手，
     * 按下的一瞬间方块会先跳一下，那一下就把两段动画拆开了。
     */
    @Test
    fun seedExitStartsExactlyAtTheWidget() {
        val seed = SeedShape(Rect(876f, 1896f, 1020f, 2040f), 48f)
        val exit = shrunkenRound(seed, progress = 0f, roundOut = true)
        val boundary = dilatedRound(seed, 0f)
        assertEquals(boundary.left, exit.left, 1e-4f)
        assertEquals(boundary.top, exit.top, 1e-4f)
        assertEquals(boundary.right, exit.right, 1e-4f)
        assertEquals(boundary.bottom, exit.bottom, 1e-4f)
        assertEquals(boundary.topLeftCornerRadius.x, exit.topLeftCornerRadius.x, 1e-4f)
    }

    /** 退场形状与边界同族：两者都朝「正圆」走，圆角比 r/side 单调升向 1/2 */
    @Test
    fun seedExitRoundsTowardCircleLikeTheBoundary() {
        val side = 144f
        val seed = SeedShape(Rect(0f, 0f, side, side), 48f)
        var prev = 0f
        for (i in 0..20) {
            val p = i / 20f
            val r = shrunkenRound(seed, p, roundOut = true)
            val w = r.width
            val ratio = r.topLeftCornerRadius.x / w
            assertTrue("roundness must not decrease", ratio >= prev - 1e-4f)
            assertTrue("roundness never exceeds a circle", ratio <= 0.5f + 1e-4f)
            prev = ratio
        }
        // 收口时是正圆，与边界盖满整屏时同一种形状
        val end = shrunkenRound(seed, 1f, roundOut = true)
        assertEquals(0.5f, end.topLeftCornerRadius.x / end.width, 1e-4f)
    }

    /** 内缩是四边等速的腐蚀算子，不是按比例缩：宽度单调收到短边的一半 */
    @Test
    fun seedExitInsetsFromEveryEdge() {
        val seed = SeedShape(Rect(0f, 0f, 144f, 144f), 48f)
        val mid = shrunkenRound(seed, 0.44f, roundOut = true)
        val quarter = shrunkenRound(seed, 1f, roundOut = true)
        // 四边各自内移相同距离 ⇒ 中心不动
        assertEquals(72f, (mid.left + mid.right) / 2f, 1e-3f)
        assertEquals(72f, (mid.top + mid.bottom) / 2f, 1e-3f)
        assertEquals(72f, quarter.width, 1e-3f)
        assertTrue("内缩后仍在原矩形内", mid.left >= seed.bounds.left)
    }

    /** 素墨：不圆不缩，轮廓原样，只靠透明度走 */
    @Test
    fun stillProfileKeepsTheOriginalShape() {
        val seed = SeedShape(Rect(0f, 0f, 144f, 144f), 48f)
        for (i in 0..10) {
            val r = shrunkenRound(seed, i / 10f, roundOut = false)
            assertEquals(0f, r.left, 1e-4f)
            assertEquals(144f, r.right, 1e-4f)
            assertEquals(48f, r.topLeftCornerRadius.x, 1e-4f)
        }
    }

    /** 三层退场顺序：字形早于本体走完（container transform 的 fadeMode = OUT） */
    @Test
    fun glyphLeavesBeforeTheContainer() {
        assertEquals(1f, seedAlpha(0f), 1e-6f)
        assertEquals(1f, seedGlyphAlpha(0f), 1e-6f)
        // 字形走完那一刻（0.40），本体还必须明显可见——否则先没的是方块、只剩一个叉
        assertEquals(0f, seedGlyphAlpha(0.40f), 1e-6f)
        assertTrue("本体此刻必须还在", seedAlpha(0.40f) > 0.45f)
        // 转的过程中（≤0.24）两者都得满不透明，那 170ms 的旋转才看得见
        assertEquals(1f, seedAlpha(0.18f), 1e-6f)
        assertEquals(1f, seedGlyphAlpha(0.24f), 1e-6f)
        // 本体在页面还明显在扩张时就化干净，不拖到贴边之后
        assertEquals(0f, seedAlpha(0.62f), 1e-6f)
        assertEquals(0f, seedAlpha(1f), 1e-6f)
        var prev = 1f
        for (i in 0..20) {
            val a = seedAlpha(i / 20f)
            assertTrue("alpha must not increase", a <= prev + 1e-6f)
            prev = a
        }
    }

    /**
     * 颜色移交：描边**先涨后落**，且第 0 帧必须是 0。
     *
     * 第 0 帧不能有描边——Stroke 居中对齐，一半画在轮廓外，若此刻就满不透明，
     * 方块四周会先多出一圈同色 1dp，读作「方块大了一点」，那一下就是接缝。
     * 涨满时（0.10）边界已外扩 190px，整条线都在方块之外，橙色才开始读作「页面的边」。
     */
    @Test
    fun seedEdgeCarriesTheColorOutward() {
        assertEquals(0f, seedEdgeAlpha(0f), 1e-6f)
        assertEquals(1f, seedEdgeAlpha(0.10f), 1e-6f)
        assertEquals(1f, seedEdgeAlpha(0.28f), 1e-6f)
        assertTrue("四分之一行程时仍应看得见", seedEdgeAlpha(0.25f) > 0.9f)
        assertEquals(0f, seedEdgeAlpha(0.55f), 1e-6f)
        assertEquals(0f, seedEdgeAlpha(1f), 1e-6f)
        // 涨满之后必须单调不增，否则会读成「橙边又亮了一下」
        var prev = 1f
        for (i in 10..20) {
            val a = seedEdgeAlpha(i / 20f)
            assertTrue("edge alpha must not increase after peak", a <= prev + 1e-6f)
            prev = a
        }
    }
}
