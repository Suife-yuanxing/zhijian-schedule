package com.zhijian.schedule.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 卡面文字的 3D 深度：视差位移、深度阶梯的偏序、字形明暗面的量纲。
 *
 * 这三条都是"改一个数就会让整张卡看起来变平/变脏/出重影"的地方，用纯函数钉住，
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
        // 「第 9 周」是整卡最大视觉重心 ⇒ 最高层；贴纸自带底色 ⇒ 不叠明暗（会糊成双边框）
        assertEquals(10.dp, CardDepthLadder.Hero.depth)
        assertEquals(0f, CardDepthLadder.Chip.relief, 1e-6f)
        assertTrue(CardDepthLadder.Hero.relief >= CardDepthLadder.Title.relief)
        assertTrue(CardDepthLadder.Title.relief > CardDepthLadder.Unit.relief)
    }

    @Test
    fun `ink bevel is a gradient inside the glyph and never a second outline`() {
        val base = Color(0xFF1C1B1F)
        // 贴纸层保持本色：三阶全等 ⇒ 渐变退化成一个颜色，画不出边
        val flat = CardDepthLadder.Chip.inkVolume(base)
        assertEquals(base, flat.top)
        assertEquals(base, flat.mid)
        assertEquals(base, flat.bottom)

        val hero = CardDepthLadder.Hero.inkVolume(base)
        // 上缘比本色亮、下缘比本色暗，中段回落到本色（主色不被抽干）
        assertTrue(hero.top.luminance() > base.luminance())
        assertTrue(hero.bottom.luminance() < base.luminance())
        assertEquals(base, hero.mid)
        // 明暗是"上亮下暗"单调过渡：反过来就读成字被顶住，立体方向错
        assertTrue(hero.top.luminance() > hero.mid.luminance())
        assertTrue(hero.mid.luminance() > hero.bottom.luminance())
    }

    @Test
    fun `ink bevel scales with relief and stays legible`() {
        val base = Color(0xFF1C1B1F)
        val hero = CardDepthLadder.Hero.inkVolume(base)
        val unit = CardDepthLadder.Unit.inkVolume(base)
        // 层级越靠前，明暗台阶越大
        assertTrue(
            hero.top.luminance() - base.luminance() >
                unit.top.luminance() - base.luminance(),
        )
        // 但台阶都有上限：字要先读得清，才谈立体
        assertTrue(hero.top.red <= 1f && hero.bottom.red >= 0f)
        assertTrue(hero.top.alpha == 1f && hero.bottom.alpha == 1f)
    }

    @Test
    fun `dark theme pushes the bevel down instead of up`() {
        val base = Color(0xFFE6E1E5)
        val light = CardDepthLadder.Hero.inkVolume(base, dark = false)
        val dark = CardDepthLadder.Hero.inkVolume(base, dark = true)
        // 深色主题的卡面本身接近黑：提亮留少一点，压暗多留一点，厚度才读得出来
        assertTrue(
            light.top.luminance() - base.luminance() >
                dark.top.luminance() - base.luminance(),
        )
        assertTrue(
            base.luminance() - dark.bottom.luminance() >
                base.luminance() - light.bottom.luminance(),
        )
    }

    @Test
    fun `relief zero layers produce no bevel at all`() {
        // Face 层留在卡面：既不位移，也不该有任何明暗台阶
        val base = Color(0xFF333333)
        val face = CardDepthLadder.Face.inkVolume(base)
        assertEquals(base, face.top)
        assertEquals(base, face.mid)
        assertEquals(base, face.bottom)
        // 笔芯退化成纯色：画出来与原色逐通道相等，不存在"看不见的第二层"
        assertEquals(face.top.toArgb(), face.bottom.toArgb())
    }

    private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
}
