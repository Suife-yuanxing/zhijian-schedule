package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionKeyframesTest {

    private val keys = arrayOf(0f to 1f, 0.5f to 2f, 1f to 1f)

    @Test
    fun clampsOutsideBothEnds() {
        assertEquals(1f, keyframed(-0.3f, *keys), 1e-6f)
        assertEquals(1f, keyframed(1.4f, *keys), 1e-6f)
    }

    @Test
    fun returnsExactKeyframeValues() {
        assertEquals(1f, keyframed(0f, *keys), 1e-6f)
        assertEquals(2f, keyframed(0.5f, *keys), 1e-6f)
        assertEquals(1f, keyframed(1f, *keys), 1e-6f)
    }

    @Test
    fun interpolatesBetweenKeyframes() {
        assertEquals(1.5f, keyframed(0.25f, *keys), 1e-6f)
        assertEquals(1.5f, keyframed(0.75f, *keys), 1e-6f)
    }

    @Test
    fun tabBounceChannelsRestAtBothEnds() {
        // 弹跳各通道在 0 与 1 处都必须是静息值，复点重播时 snapTo(0) 才不会跳变
        for (p in listOf(0f, 1f)) {
            assertEquals(1f, keyframed(p, 0f to 1f, 0.13f to 1.07f, 0.38f to 0.95f, 0.63f to 1.02f, 1f to 1f), 1e-6f)
            assertEquals(0f, keyframed(p, 0f to 0f, 0.27f to -5.5f, 0.58f to -0.8f, 0.73f to -1.6f, 1f to 0f), 1e-6f)
        }
    }
}
