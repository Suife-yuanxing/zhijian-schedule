package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 主题扩散半径：圆心取自被点胶囊，半径必须覆盖到屏幕最远角，
 * 否则圆还没铺满就停住，画面上会留一条旧主题的边。
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
                            "center=($x,$y) corner=($cx,$cy) radius=$radius",
                            hypot(cx - x, cy - y) <= radius + 1e-3f,
                        )
                    }
                }
            }
        }
    }

    /**
     * 反向（返回）的半径必须从「盖满整屏」单调收到「被点控件」。
     *
     * 反向曾经叠过一层整块 zoom + fade 想加强存在感，结果圆被缩一遍、图又被缩一遍，
     * 画出来的残影既不在按钮中心、边界也不是圆。这条测试把「只缩圆」这个纯几何契约钉死：
     * 一旦有人再往反向的半径里掺入缩放，下面的单调性与端点就会直接红。
     */
    @Test
    fun `反向半径从满屏单调收到按钮`() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        // 加号真实位置：48dp @3x，右下角
        val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)
        assertTrue(s.requestNavigationBack(fab, Color.Black) { })
        s.beginReveal(null)
        val active = s.active!!
        val far =
            revealRadius(
                active.center.x,
                active.center.y,
                s.hostSize.width.toFloat(),
                s.hostSize.height.toFloat(),
            ) + 2f

        // t=0：起手就得盖满，否则返程第一帧四角会先漏出课表
        s.progress.floatValue = 0f
        assertEquals(far, s.radiusOf(active), 1e-2f)
        // t=1：收成加号那么大，剩下的交给真实的加号接手
        s.progress.floatValue = 1f
        assertEquals(active.startRadius, s.radiusOf(active), 1e-2f)

        var prev = Float.MAX_VALUE
        for (i in 0..20) {
            s.progress.floatValue = i / 20f
            val r = s.radiusOf(active)
            assertTrue("t=$i/20 radius=$r 应单调不增", r <= prev + 1e-3f)
            assertTrue("t=$i/20 radius=$r 不应超过满屏", r <= far + 1e-3f)
            assertTrue("t=$i/20 radius=$r 不应小于加号", r >= active.startRadius - 1e-3f)
            prev = r
        }
    }

    /** 同一进度下正向与反向互为镜像：fwd(t) + rev(t) == 加号半径 + 满屏半径 */
    @Test
    fun `正向与反向在同一进度上互为镜像`() {
        val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)

        val fwd = RevealState()
        fwd.hostSize = IntSize(1080, 2376)
        fwd.requestNavigation(fab, Color.Black) { }
        fwd.beginReveal(null)
        val a1 = fwd.active!!

        val rev = RevealState()
        rev.hostSize = IntSize(1080, 2376)
        rev.requestNavigationBack(fab, Color.Black) { }
        rev.beginReveal(null)
        val a2 = rev.active!!

        assertEquals(a1.center, a2.center)
        assertEquals(a1.startRadius, a2.startRadius, 1e-3f)

        val far =
            revealRadius(
                a1.center.x,
                a1.center.y,
                fwd.hostSize.width.toFloat(),
                fwd.hostSize.height.toFloat(),
            ) + 2f
        for (i in 0..10) {
            val t = i / 10f
            fwd.progress.floatValue = t
            rev.progress.floatValue = t
            assertEquals(
                "t=$t 正反向应为镜像",
                a1.startRadius + far,
                fwd.radiusOf(a1) + rev.radiusOf(a2),
                1e-2f,
            )
        }
    }
}
