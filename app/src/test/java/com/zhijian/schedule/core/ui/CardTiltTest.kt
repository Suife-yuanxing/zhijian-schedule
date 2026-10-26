package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
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

    // ---- 焦点大卡（我的课表 / 今日正在上课）共用一份参数 ----

    @Test
    fun `focus card amplifies tilt, glare and lift over the base style`() {
        val base = CardTiltStyle(
            maxTiltDegrees = 13f,
            glareAlpha = 0.4f,
            liftScale = 0.028f,
            liftElevation = 24.dp,
            glareShade = 0.13f,
            perspectiveScale = 1.45f,
        )
        val focus = focusTiltStyle(base)
        assertEquals(17f, focus.maxTiltDegrees, 1e-5f)
        assertEquals(0.42f, focus.glareAlpha, 1e-5f)
        assertEquals(0.0336f, focus.liftScale, 1e-5f)
        assertEquals(34.dp, focus.liftElevation)
        // 与倾动无关的通道原样继承，别顺手改掉浅色纸面的背光压暗
        assertEquals(0.13f, focus.glareShade, 1e-5f)
        // 焦点卡反而把相机放**远**：960px 宽的大卡左右各只剩 60px，17° 下相机太近，
        // 近边会顶出屏幕被裁。大卡的戏剧性靠更大的倾角补，不靠更近的相机。
        assertEquals(1.70f, focus.perspectiveScale, 1e-5f)
        assertTrue(focus.perspectiveScale > base.perspectiveScale)
    }

    @Test
    fun `focus card glare is clamped to full opacity`() {
        // 玻璃浅色主题的基准反光已经是满不透明；×1.05 之后必须被 1.0 截住，
        // 否则 Color.copy(alpha = 1.05f) 会溢出成非法通道值
        val brightest = CardTiltStyle(14f, 1f, 0.034f, 30.dp, 0.13f, 1.45f)
        assertEquals(1f, focusTiltStyle(brightest).glareAlpha, 1e-5f)
    }

    @Test
    fun `perspective scale stays in the safe band`() {
        // 相机距离低于约 1.1 倍卡长时远端会被压到相机后面（z > P），卡片撕裂
        val base = CardTiltStyle(13f, 0.95f, 0.028f, 24.dp, 0.13f, 1.45f)
        assertTrue(focusTiltStyle(base).perspectiveScale >= 1.1f)
        assertTrue(base.perspectiveScale >= 1.1f)
    }

    // ---- 反光几何（业界取法：farthest-corner 半径 + 过指腹的定向扫光带）----

    @Test
    fun `broad radius reaches the farthest corner`() {
        // 左上角按下：最远的角是右下角，半径就是整条对角线
        val g = glareGeometry(Offset(0f, 0f), size)
        assertEquals(hypot(100.0, 50.0).toFloat(), g.broadRadius, 1e-3f)
        // 正中按下：四个角一样远
        val c = glareGeometry(Offset(50f, 25f), size)
        assertEquals(hypot(50.0, 25.0).toFloat(), c.broadRadius, 1e-3f)
    }

    @Test
    fun `broad radius always covers the whole card`() {
        for (x in listOf(0f, 17f, 50f, 83f, 100f)) {
            for (y in listOf(0f, 9f, 25f, 41f, 50f)) {
                val g = glareGeometry(Offset(x, y), size)
                listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, 50f), Offset(100f, 50f))
                    .forEach { corner ->
                        assertTrue(
                            "corner $corner outside broad radius at ($x,$y)",
                            (g.center - corner).getDistance() <= g.broadRadius + 1e-3f,
                        )
                    }
            }
        }
    }

    @Test
    fun `core radius never collapses on a wide card`() {
        val wide = glareGeometry(Offset(50f, 25f), Size(100f, 50f))
        assertEquals(30f, wide.coreRadius, 1e-3f) // 长边 30 与短边 29 里取大
    }

    @Test
    fun `sideways press lays the sheen band vertical`() {
        // 右侧按下：卡心→指腹朝 +x，光带垂直于它 ⇒ 竖直的一条
        val g = glareGeometry(Offset(100f, 25f), size)
        assertEquals(25f, g.sheenStart.y, 1e-3f)
        assertEquals(25f, g.sheenEnd.y, 1e-3f)
        assertTrue(g.sheenStart.x < g.center.x)
        assertTrue(g.sheenEnd.x > g.center.x)
    }

    @Test
    fun `downward press lays the sheen band horizontal`() {
        // 下侧按下：卡心→指腹朝 +y，光带垂直于它 ⇒ 水平的一条
        val g = glareGeometry(Offset(50f, 50f), size)
        assertEquals(50f, g.sheenStart.x, 1e-3f)
        assertEquals(50f, g.sheenEnd.x, 1e-3f)
        assertTrue(g.sheenStart.y < g.center.y)
        assertTrue(g.sheenEnd.y > g.center.y)
    }

    @Test
    fun `sheen strength follows the tilt scale`() {
        // 卡心：不倾斜 ⇒ 没有定向反射
        assertEquals(0f, glareGeometry(Offset(50f, 25f), size).sheenStrength, 1e-5f)
        // 边上：倾角已经到最大 ⇒ 反射最亮
        assertEquals(1f, glareGeometry(Offset(100f, 25f), size).sheenStrength, 1e-5f)
        assertEquals(1f, glareGeometry(Offset(50f, 50f), size).sheenStrength, 1e-5f)
        assertEquals(1f, glareGeometry(Offset(100f, 50f), size).sheenStrength, 1e-5f)
        // 与倾角共用同一套归一化，不能是另一把尺子
        for (x in listOf(-20f, 30f, 75f, 140f)) {
            for (y in listOf(-10f, 12f, 40f, 90f)) {
                val p = Offset(x, y)
                val n = tiltNormalized(p, size)
                val expected = maxOf(abs(n.x), abs(n.y))
                assertEquals(expected, glareGeometry(p, size).sheenStrength, 1e-5f)
            }
        }
    }

    @Test
    fun `pointer outside the card is clamped before measuring`() {
        val g = glareGeometry(Offset(-40f, 900f), size)
        assertEquals(0f, g.center.x, 1e-3f)
        assertEquals(50f, g.center.y, 1e-3f)
        assertEquals(hypot(100.0, 50.0).toFloat(), g.broadRadius, 1e-3f)
    }

    @Test
    fun `degenerate glare geometry stays finite`() {
        val g = glareGeometry(Offset(10f, 10f), Size.Zero)
        assertTrue(g.broadRadius.isFinite() && g.broadRadius > 0f)
        assertTrue(g.coreRadius.isFinite() && g.coreRadius > 0f)
        assertEquals(0f, g.sheenStrength, 1e-5f)
        assertTrue(g.sheenStart.x.isFinite() && g.sheenStart.y.isFinite())
        assertTrue(g.sheenEnd.x.isFinite() && g.sheenEnd.y.isFinite())
    }
}
