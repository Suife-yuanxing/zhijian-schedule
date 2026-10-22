package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸倾动的几何：归一化 → 倾角。
 *
 * 这些函数不碰 Android，直接钉死「中心不倾、右侧 rotateY 为负、下侧 rotateX 为正」
 * 和越界夹紧，避免以后调参数时把方向或幅度改歪。
 */
class CardTiltTest {

    private val size = Size(100f, 50f)

    @Test
    fun `center maps to zero tilt`() {
        val n = tiltNormalized(Offset(50f, 25f), size)
        assertEquals(0f, n.x, 1e-5f)
        assertEquals(0f, n.y, 1e-5f)
        val angles = tiltAngles(n, maxDegrees = 8f)
        assertEquals(0f, angles.rotationX, 1e-5f)
        assertEquals(0f, angles.rotationY, 1e-5f)
    }

    @Test
    fun `top left is normalized to minus one and tilts away the same corner`() {
        val n = tiltNormalized(Offset(0f, 0f), size)
        assertEquals(-1f, n.x, 1e-5f)
        assertEquals(-1f, n.y, 1e-5f)
        val angles = tiltAngles(n, maxDegrees = 8f)
        assertEquals(-8f, angles.rotationX, 1e-5f)
        assertEquals(8f, angles.rotationY, 1e-5f)
    }

    @Test
    fun `bottom right mirrors the top left`() {
        val n = tiltNormalized(Offset(100f, 50f), size)
        assertEquals(1f, n.x, 1e-5f)
        assertEquals(1f, n.y, 1e-5f)
        val angles = tiltAngles(n, maxDegrees = 8f)
        assertEquals(8f, angles.rotationX, 1e-5f)
        assertEquals(-8f, angles.rotationY, 1e-5f)
    }

    @Test
    fun `drag outside the card clamps to the edge`() {
        val n = tiltNormalized(Offset(-40f, 120f), size)
        assertEquals(-1f, n.x, 1e-5f)
        assertEquals(1f, n.y, 1e-5f)
    }

    @Test
    fun `angles never exceed the configured maximum`() {
        for (x in listOf(-1f, -0.55f, 1f, 2f)) {
            for (y in listOf(-1f, 0.3f, 1f, -3f)) {
                val angles = tiltAngles(Offset(x, y), maxDegrees = 9f)
                assertTrue(abs(angles.rotationX) <= 9f + 1e-5f)
                assertTrue(abs(angles.rotationY) <= 9f + 1e-5f)
            }
        }
    }

    @Test
    fun `zero or negative max disables the tilt`() {
        assertEquals(0f, tiltAngles(Offset(1f, 1f), maxDegrees = 0f).rotationX, 1e-5f)
        assertEquals(0f, tiltAngles(Offset(1f, 1f), maxDegrees = -4f).rotationY, 1e-5f)
    }

    @Test
    fun `degenerate size keeps the math safe`() {
        val n = tiltNormalized(Offset(10f, 10f), Size.Zero)
        assertEquals(0f, n.x, 1e-5f)
        assertEquals(0f, n.y, 1e-5f)
    }
}
