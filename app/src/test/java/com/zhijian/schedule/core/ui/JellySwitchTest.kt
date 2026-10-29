package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 果冻开关的几何与速度映射。
 *
 * 钉死几条不变量：
 * - 静止是正圆，未选中 16dp / 选中 24dp，两端内边距分别是 8dp / 4dp；
 * - 中心行程恒为 20dp，与手柄直径无关（M3 手柄生长时不会「走歪」的前提）；
 * - 拉伸时是「宽 > 高」的胶囊，且体积守恒（面积不会大幅偏离正圆）；
 * - 宽度被轨道边界夹窄时，高度要把面积补回来——撞墙读作挤压，不是缩水；
 * - 任何进度 / 过冲 / 拉伸 / 直径下都不越出轨道。
 *
 * 以后调弹簧刚度、拉伸幅度或手柄尺寸时，这几条必须还成立。
 */
class JellySwitchTest {
    private val track = Size(52f, 32f)
    private val dOn = 24f
    private val dOff = 16f
    private val maxStretchOn = dOn * 0.375f
    private val maxStretchOff = dOff * 0.375f
    private val squash = 0.26f

    private fun thumb(
        progress: Float,
        stretch: Float = 0f,
        diameter: Float = dOn,
        maxStretch: Float = maxStretchOn,
    ) = jellyThumbRect(track, progress, stretch, diameter, maxStretch, squash)

    // ---- 静止态 ----

    @Test
    fun `checked knob is a circle inset by 4dp`() {
        val r = thumb(progress = 1f)
        assertEquals(24f, r.left, 1e-4f)
        assertEquals(48f, r.right, 1e-4f)
        assertEquals(24f, r.width, 1e-4f)
        assertEquals(24f, r.height, 1e-4f)
        assertEquals(4f, r.top, 1e-4f)
    }

    @Test
    fun `unchecked knob is a smaller dot inset by 8dp`() {
        val r = thumb(progress = 0f, diameter = dOff, maxStretch = maxStretchOff)
        assertEquals(8f, r.left, 1e-4f)
        assertEquals(24f, r.right, 1e-4f)
        assertEquals(16f, r.width, 1e-4f)
        assertEquals(16f, r.height, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
    }

    @Test
    fun `unchecked dot also keeps 8dp from the far end`() {
        val r = thumb(progress = 1f, diameter = dOff, maxStretch = maxStretchOff)
        assertEquals(28f, r.left, 1e-4f)
        assertEquals(44f, r.right, 1e-4f)
    }

    @Test
    fun `the center path is 20dp long whatever the diameter is`() {
        // travel = W - d - (H - d) = W - H，与直径无关；手柄变大只是内边距 8dp → 4dp
        for (step in 0..8) {
            val d = dOff + (dOn - dOff) * step / 8f
            assertEquals(16f, thumb(0f, diameter = d).center.x, 1e-4f)
            assertEquals(36f, thumb(1f, diameter = d).center.x, 1e-4f)
        }
    }

    @Test
    fun `halfway the knob is centered`() {
        assertEquals(26f, thumb(0.5f).center.x, 1e-4f)
    }

    // ---- 拉伸 ----

    @Test
    fun `stretching makes a capsule wider than tall`() {
        val r = thumb(progress = 0.5f, stretch = 1f)
        assertEquals(33f, r.width, 1e-4f)
        assertEquals(dOn * (1f - squash), r.height, 1e-4f)
        assertTrue(r.width > r.height)
    }

    @Test
    fun `stretch keeps the knob vertically centered`() {
        for (s in 0..10) {
            val r = thumb(progress = 0.5f, stretch = s / 10f)
            assertEquals(track.height / 2f, r.center.y, 1e-4f)
        }
    }

    @Test
    fun `stretching preserves the volume of the circle`() {
        // 宽了必须矮。线性压缩是面积守恒的近似：面积比 = (1+0.375s)(1-0.26s) = 1 + 0.115s - 0.0975s²，
        // 与直径无关，在 s ≈ 0.59 处取到最大 +3.39%，两端分别是 0（正圆）与 +1.75%（拉满）。
        for (d in listOf(dOff, 20f, dOn)) {
            val maxS = d * 0.375f
            for (step in 0..20) {
                val r = thumb(0.5f, step / 20f, diameter = d, maxStretch = maxS)
                val area = r.width * r.height
                assertTrue("d=$d s=$step area=$area", area <= d * d * 1.034f)
                assertTrue("d=$d s=$step area=$area", area >= d * d * 0.999f)
            }
        }
    }

    @Test
    fun `a clamped capsule trades width into height instead of shrinking`() {
        // 过冲到 1.13 时请求宽度 33 会被右边界夹到 29.9；面积守恒让高度从 17.76 长到 19.26
        val r = thumb(progress = 1.13f, stretch = 1f)
        val linear = dOn * (1f - squash)
        assertTrue("width=${r.width}", r.width < 33f)
        assertTrue("height=${r.height} linear=$linear", r.height > linear)
        assertEquals(dOn * dOn, r.width * r.height, 1f)
    }

    // ---- 边界 ----

    @Test
    fun `never leaves the track for any progress, stretch or diameter`() {
        // 0.15 覆盖弹簧过冲（阻尼比 0.55 时约 12.6%）
        for (p in listOf(-0.15f, 0f, 0.13f, 0.5f, 0.87f, 1f, 1.13f)) {
            for (step in 0..10) {
                for (d in listOf(dOff, dOn)) {
                    val s = step / 10f
                    val r = thumb(p, s, diameter = d, maxStretch = d * 0.375f)
                    assertTrue("p=$p d=$d s=$s left=${r.left}", r.left >= -1e-4f)
                    assertTrue("p=$p d=$d s=$s right=${r.right}", r.right <= track.width + 1e-4f)
                    assertTrue("p=$p d=$d s=$s top=${r.top}", r.top >= -1e-4f)
                    assertTrue("p=$p d=$d s=$s bottom=${r.bottom}", r.bottom <= track.height + 1e-4f)
                    assertTrue("p=$p d=$d s=$s width=${r.width}", r.width > 0f)
                }
            }
        }
    }

    @Test
    fun `overshoot pushes into the padding but never past the edge`() {
        // 12.6% 过冲落在 4dp 内边距里：到站时手柄确实会「顶一下」留白，但顶不出轨道
        val over = thumb(progress = 1.125f)
        assertTrue(over.right > 48f)
        assertTrue(over.right <= track.width)
    }

    @Test
    fun `center moves monotonically with progress`() {
        var last = Float.NEGATIVE_INFINITY
        for (i in 0..20) {
            val c = thumb(i / 20f).center.x
            assertTrue("step $i center=$c", c >= last)
            last = c
        }
    }

    // ---- 速度映射 ----

    @Test
    fun `stretch is zero at rest and saturates at the spring peak speed`() {
        assertEquals(0f, jellyStretch(0f), 1e-4f)
        assertEquals(1f, jellyStretch(10.69f), 1e-4f)
        assertEquals(1f, jellyStretch(40f), 1e-4f)
        assertTrue(jellyStretch(5f) > 0.4f)
    }

    @Test
    fun `stretch ignores travel direction`() {
        assertEquals(jellyStretch(6f), jellyStretch(-6f), 1e-6f)
    }

    @Test
    fun `holding the knob widens it by 17 percent at either size`() {
        assertEquals(0f, jellyStretch(0f, grab = 0f), 1e-4f)
        assertEquals(0.45f, jellyStretch(0f, grab = 1f), 1e-4f)
        assertEquals(1f, jellyStretch(20f, grab = 1f), 1e-4f)
        // 24 → 28.05，16 → 18.7：同一个相对幅度，和 M3 按下档 24→28dp 同量级
        val on = thumb(0.5f, jellyStretch(0f, grab = 1f))
        val off = thumb(0.5f, jellyStretch(0f, grab = 1f), diameter = dOff, maxStretch = maxStretchOff)
        assertEquals(0.17f, on.width / dOn - 1f, 0.005f)
        assertEquals(0.17f, off.width / dOff - 1f, 0.005f)
    }
}
