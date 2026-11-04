package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉关闭时内容「退开」的量表：[SheetRecedeModel]。
 *
 * 为什么这些断言值得写：这三个公式只服务一种极难复现的场景——手指正拖着面板往下走的那
 * 两三百毫秒。调错不会崩、不会报错，只会让下拉变得迟钝（进度恒为 0）或者过火
 * （一下就缩到最小、露出底板）。真机上要复现得刚好抓到那一帧，所以把它按边界逐条钉住。
 */
class SheetRecedeModelTest {
    private val rest = 720f
    private val travel = 480f

    @Test
    fun `没有拖动时进度为零`() {
        assertEquals(0f, SheetRecedeModel.progressOf(rest, rest, travel), 0.0001f)
    }

    @Test
    fun `拉到整段行程时进度为一`() {
        assertEquals(1f, SheetRecedeModel.progressOf(rest + travel, rest, travel), 0.0001f)
    }

    @Test
    fun `超过整段行程仍钳在一`() {
        // 面板被拖到屏幕之外时偏移可能远大于内容高度；若不钳制，
        // 缩放会缩到负数、透明度变成负值被图层丢弃，内容直接消失
        assertEquals(1f, SheetRecedeModel.progressOf(rest + travel * 3f, rest, travel), 0.0001f)
    }

    @Test
    fun `面板向上回弹时进度不会变负`() {
        // 松手回弹的最后一两帧会略高于停稳位；若让负值漏进来，内容会短暂比原尺寸更大
        assertEquals(0f, SheetRecedeModel.progressOf(rest - 40f, rest, travel), 0.0001f)
    }

    @Test
    fun `半程进度正好取中点`() {
        assertEquals(0.5f, SheetRecedeModel.progressOf(rest + travel / 2f, rest, travel), 0.0001f)
    }

    @Test
    fun `行程为零时进度为零而不是无穷大`() {
        // 内容尚未测量完时行程是 0；除以它会得到 Infinity，缩放随即变成 -Infinity
        assertEquals(0f, SheetRecedeModel.progressOf(rest + 100f, rest, 0f), 0.0001f)
    }

    @Test
    fun `行程为负时同样按零处理`() {
        assertEquals(0f, SheetRecedeModel.progressOf(rest + 100f, rest, -travel), 0.0001f)
    }

    @Test
    fun `偏移是 NaN 时进度为零`() {
        // 面板刚组合、锚点还没就绪时偏移是 NaN。这个值一旦漏进图层，
        // graphicsLayer 会收到 NaN 缩放，整块内容在那一帧直接不绘制
        assertEquals(0f, SheetRecedeModel.progressOf(Float.NaN, rest, travel), 0.0001f)
    }

    @Test
    fun `基线是无穷大时进度为零`() {
        assertEquals(0f, SheetRecedeModel.progressOf(rest + 100f, Float.POSITIVE_INFINITY, travel), 0.0001f)
    }

    @Test
    fun `曲线与手指一比一`() {
        // 面板是 1:1 跟手走的，退开量必须同一条比例，否则读作「内容被手指拖住」
        for (step in 0..10) {
            val p = step / 10f
            assertEquals(p, SheetRecedeModel.curveOf(p), 0.0001f)
        }
    }

    @Test
    fun `曲线两端严格落在零与一`() {
        assertEquals(0f, SheetRecedeModel.curveOf(0f), 0.0001f)
        assertEquals(1f, SheetRecedeModel.curveOf(1f), 0.0001f)
    }

    @Test
    fun `杂动折算出的退开量小到看不见`() {
        // 十几像素的抖动摊在整块面板高度上只折出 1% 左右的退开量，
        // 这是「不需要专门加死区」的依据，而不是靠压缩前段曲线
        val jitterProgress = 14f / 1287f
        assertTrue("杂动不该产生可见退开", SheetRecedeModel.specAt(jitterProgress).alpha > 0.99f)
    }

    @Test
    fun `进度为零时内容原样`() {
        val spec = SheetRecedeModel.specAt(0f)
        assertEquals(1f, spec.scale, 0.0001f)
        assertEquals(1f, spec.alpha, 0.0001f)
        assertEquals(RecedeSpec.None, spec)
    }

    @Test
    fun `进度为一时缩到最小档并留一半以上不透明度`() {
        val spec = SheetRecedeModel.specAt(1f)
        assertEquals(1f - SheetRecedeModel.MAX_SHRINK, spec.scale, 0.0001f)
        assertEquals(1f - SheetRecedeModel.MAX_FADE, spec.alpha, 0.0001f)
    }

    @Test
    fun `缩小的幅度刻意远小于淡出的幅度`() {
        // 缩小过头会让面板两侧露出底板、读成两块东西；淡出才是「退后」的主要信号。
        // 这条断言钉住这个比例关系，防止日后为了「更明显」把两个数一起调大。
        assertTrue(SheetRecedeModel.MAX_SHRINK < SheetRecedeModel.MAX_FADE / 4f)
    }

    @Test
    fun `缩放与透明度都随进度单调变弱`() {
        var lastScale = Float.MAX_VALUE
        var lastAlpha = Float.MAX_VALUE
        for (step in 0..10) {
            val spec = SheetRecedeModel.specAt(step / 10f)
            assertTrue("scale 在 step=$step 处回升了", spec.scale <= lastScale)
            assertTrue("alpha 在 step=$step 处回升了", spec.alpha <= lastAlpha)
            lastScale = spec.scale
            lastAlpha = spec.alpha
        }
    }

    @Test
    fun `越界的进度被钳到两端`() {
        assertEquals(SheetRecedeModel.specAt(0f), SheetRecedeModel.specAt(-2f))
        assertEquals(SheetRecedeModel.specAt(1f), SheetRecedeModel.specAt(9f))
        // NaN 一律当作「没在拖」，而不是当作拖到底：错成后者会让面板一组合就缩一下
        assertEquals(SheetRecedeModel.specAt(0f), SheetRecedeModel.specAt(Float.NaN))
    }

    @Test
    fun `淡到底时仍然看得见内容`() {
        // 刻意不淡到 0：遮罩已经在压暗，再让内容先于容器消失会暴露「下面什么都没有」
        assertTrue(SheetRecedeModel.specAt(1f).alpha > 0.5f)
    }
}
