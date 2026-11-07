package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加号 ↔ 叉号形变规则的回归闸门。
 *
 * 形变本身是动画，但它同时决定两件可断言的事：转角到进度的映射（返回时要接着转），
 * 以及无障碍描述在哪一刻改口。这些错了不会崩，只会表现成「点了好像没反应」，
 * 所以用纯函数断言钉住，而不是等真机逐帧看。
 */
class FabMorphTest {
    @Test
    fun `进度到角度是线性的 且两端正好是加号与叉号`() {
        assertEquals(0f, FabMorph.rotationDegrees(0f), 1e-6f)
        assertEquals(45f, FabMorph.rotationDegrees(1f), 1e-6f)
        assertEquals(22.5f, FabMorph.rotationDegrees(0.5f), 1e-6f)
    }

    @Test
    fun `进度越界被夹住 不会转过 45 度`() {
        assertEquals(0f, FabMorph.rotationDegrees(-1f), 1e-6f)
        assertEquals(45f, FabMorph.rotationDegrees(2f), 1e-6f)
        assertEquals(1f, FabMorph.progressOf(90f), 1e-6f)
        assertEquals(0f, FabMorph.progressOf(-30f), 1e-6f)
    }

    @Test
    fun `角度与进度互为反函数`() {
        for (i in 0..20) {
            val p = i / 20f
            assertEquals(p, FabMorph.progressOf(FabMorph.rotationDegrees(p)), 1e-6f)
        }
    }

    /**
     * 形变不再阻塞页面展开：这条测试守住「handoff 等待已被删除」这个结论。
     *
     * 旧版存在 handoffWaitMillis(0f) == 150 的语义（先转完再放扩散）。方块浮到裁剪层
     * 之上以后，那个等待的理由消失了；一旦有人把它加回来，点击就会重新变成「等两次动画」。
     */
    @Test
    fun `形变时长落在高频反馈区间`() {
        // NN/g：高频交互反馈 100~200ms；低于 100 读不出「变了」，高于 200 会让下次点击显迟钝
        assertTrue(FabMorph.DURATION_MILLIS in 100..200)
        // 形变与页面展开同时起跑：它不再是转场的前置条件，所以不该长过一次转场时长
        assertTrue(FabMorph.DURATION_MILLIS < Motion.DUR_REVEAL)
    }

    /** 形变曲线必须比页面展开先到位：方块在离场之前要「已经是 ×」 */
    @Test
    fun `形变在收缩开始前已经转完`() {
        val morphDone = FabMorph.DURATION_MILLIS.toFloat()
        val shrinkStart = Motion.DUR_REVEAL.toFloat()
        assertTrue("形变 " + morphDone + "ms 必须早于页面铺满 " + shrinkStart + "ms", morphDone < shrinkStart)
    }
}
