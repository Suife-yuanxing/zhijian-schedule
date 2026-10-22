package com.zhijian.schedule.core.ui

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
}
