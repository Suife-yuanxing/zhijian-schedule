package com.zhijian.schedule.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 卡面文字的 3D 深度：视差位移、深度阶梯的偏序、浮雕投影的量纲。
 *
 * 这三条都是"改一个数就会让整张卡看起来变平/变脏"的地方，用纯函数钉住，
 * 免得以后调参时把层间关系调乱（见 [CardDepth] 的 kdoc）。
 */
class CardDepthTextTest {
    /** 与焦点大卡同一份取值（[rememberFocusCardTiltStyle] 的纯函数内核在 13° 基准上的结果） */
    private val maxDegrees = 17f

    @Test
    fun `drift is proportional to depth and zero at rest`() {
        val rest = parallaxDrift(0f, 0f, maxDegrees, depthPx = 10f)
        assertEquals(0f, rest.x, 1e-6f)
        assertEquals(0f, rest.y, 1e-6f)

        // 同一角度下，深度翻倍 ⇒ 位移翻倍（层与层之间才有"错开"）
        val near = parallaxDrift(0f, -maxDegrees, maxDegrees, depthPx = 5f)
        val far = parallaxDrift(0f, -maxDegrees, maxDegrees, depthPx = 10f)
        assertEquals(5f, near.x, 1e-5f)
        assertEquals(2f * near.x, far.x, 1e-5f)
    }

    @Test
    fun `drift never exceeds the layer depth`() {
        // 线性近似的上限：倾角打满时位移恰好等于深度（1:1），层间比例可直接读作前后关系
        for (depth in listOf(3f, 5f, 7f, 10f)) {
            val d = parallaxDrift(maxDegrees, -maxDegrees, maxDegrees, depthPx = depth)
            assertEquals(depth, d.x, 1e-5f)
            assertEquals(depth, d.y, 1e-5f)
        }
    }

    @Test
    fun `pressed side lifts toward the viewer`() {
        // 右侧下压（rotationY < 0）⇒ 内容往右滑；下侧下压（rotationX > 0）⇒ 往下滑
        val right = parallaxDrift(0f, -maxDegrees, maxDegrees, depthPx = 10f)
        assertTrue(right.x > 0f)
        val bottom = parallaxDrift(maxDegrees, 0f, maxDegrees, depthPx = 10f)
        assertTrue(bottom.y > 0f)
    }

    @Test
    fun `in range drift equals the formula used by cardTiltParallax`() {
        // cardTiltParallax 的实现是 translationX = -rotationY * (depth / max)，
        // 这里逐值对齐，保证两条路径在量程内完全等价（夹取只在过冲时生效）
        val max = 17f
        val depthPx = 10f
        for (rotationX in listOf(-17f, -8.5f, 0f, 3f, 17f)) {
            for (rotationY in listOf(-17f, -2f, 0f, 7.5f, 17f)) {
                val d = parallaxDrift(rotationX, rotationY, max, depthPx)
                val pxPerDegree = depthPx / max
                assertEquals(-rotationY * pxPerDegree, d.x, 1e-5f)
                assertEquals(rotationX * pxPerDegree, d.y, 1e-5f)
            }
        }
    }

    @Test
    fun `overshoot beyond the max angle is clamped so layers keep their ratio`() {
        // 回正弹簧过冲 27%：不夹的话 hero 会甩得比 depth 更远，层间比例失真
        val max = 17f
        val overshoot = max * 1.27f
        val hero = parallaxDrift(overshoot, -overshoot, max, depthPx = 10f)
        val unit = parallaxDrift(overshoot, -overshoot, max, depthPx = 3f)
        assertEquals(10f, hero.x, 1e-5f)
        assertEquals(3f, unit.x, 1e-5f)
    }

    @Test
    fun `degenerate max degrees keeps the math finite`() {
        val d = parallaxDrift(4f, -4f, maxDegrees = 0f, depthPx = 10f)
        assertTrue(d.x.isFinite() && d.y.isFinite())
        assertTrue(abs(d.x) <= 10f + 1e-5f)
    }

    @Test
    fun `depth ladder is strictly ordered from hero to face`() {
        val ladder =
            listOf(
                CardDepthLadder.Hero,
                CardDepthLadder.Title,
                CardDepthLadder.Chip,
                CardDepthLadder.Unit,
                CardDepthLadder.Face,
            )
        for (i in 0 until ladder.lastIndex) {
            assertTrue(
                "layer $i 应比下一层更靠近观察者",
                ladder[i].depth > ladder[i + 1].depth,
            )
        }
        assertEquals(0.dp, CardDepthLadder.Face.depth)
    }

    @Test
    fun `the biggest number floats highest but the chip takes no relief`() {
        // 「第 9 周」是整卡最大视觉重心 ⇒ 最高层；贴纸自带底色 ⇒ 不叠浮雕（会糊成双边框）
        assertEquals(10.dp, CardDepthLadder.Hero.depth)
        assertEquals(0f, CardDepthLadder.Chip.relief, 1e-6f)
        assertTrue(CardDepthLadder.Hero.relief >= CardDepthLadder.Title.relief)
        assertTrue(CardDepthLadder.Title.relief > CardDepthLadder.Unit.relief)
    }

    @Test
    fun `relief shadow scales with relief and stays inside a sane alpha band`() {
        val density = FakeDensity(2.75f)
        val ink = Color(0xFF1C1B1F)
        assertNull(CardDepthLadder.Face.reliefShadow(ink, density))

        val hero = CardDepthLadder.Hero.reliefShadow(ink, density)
        val unit = CardDepthLadder.Unit.reliefShadow(ink, density)
        assertNotNull(hero)
        assertNotNull(unit)
        // 影子的 alpha 必须压在 0.45 以下：字要先读得清，才谈立体
        assertTrue(hero!!.color.alpha in 0.05f..0.40f)
        assertTrue(hero.color.alpha > unit!!.color.alpha)
        // 偏移与模糊都乘过 density：直接喂 dp 在高密度屏上会细到看不见
        assertTrue(hero.offset.y > 0f)
        assertTrue(hero.blurRadius > hero.offset.y)
    }

    @Test
    fun `dark theme relief is subtler than light`() {
        val density = FakeDensity(3f)
        val ink = Color.White
        val light = CardDepthLadder.Hero.reliefShadow(ink, density, dark = false)!!
        val dark = CardDepthLadder.Hero.reliefShadow(ink, density, dark = true)!!
        // 深色主题的卡面本来就接近黑，压重了会显脏：偏移更小、影更淡
        assertTrue(dark.offset.y < light.offset.y)
        assertTrue(dark.blurRadius < light.blurRadius)
    }

    private class FakeDensity(
        override val density: Float,
    ) : Density {
        override val fontScale: Float = 1f
    }
}
