package com.zhijian.schedule.core.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐藏调试入口的连点判定。
 *
 * 这个入口一旦失灵，代价是双向的：太灵敏会让正常用户两下点出一个英文面板，
 * 太迟钝则真要用时点不出来、于是没人再信它。两种都不是崩溃，只是安静地变难用——
 * 所以边界必须用注入的时间戳钉死，而不是靠「大概点得挺快」的体感。
 */
class DebugTapGateTest {
    @Test
    fun unlocksExactlyOnTheSeventhTap() {
        val gate = DebugTapGate()
        // 7 次，间隔 10ms：第 1..6 次都不该触发
        repeat(6) { i ->
            assertFalse("第 " + (i + 1) + " 次就提前触发了", gate.tap(i * 10L))
        }
        assertTrue("第 7 次必须触发", gate.tap(60L))
    }

    @Test
    fun counterResetsAfterUnlock() {
        val gate = DebugTapGate()
        repeat(6) { i -> gate.tap(i * 10L) }
        assertTrue(gate.tap(60L))
        // 归零之后必须重新点满 7 次：否则面板关掉后再点一下就又弹出来
        assertFalse("凑满后计数必须归零", gate.tap(70L))
        assertEquals("归零后的进度应为 1", 1, gate.progress())
    }

    @Test
    fun slowTapsRestartTheCount() {
        val gate = DebugTapGate()
        // 前 5 次正常，第 6 次晚到 2 秒（超过 1.2 秒上限）
        repeat(5) { i -> gate.tap(i * 10L) }
        assertFalse(gate.tap(5_000L))
        assertEquals("超时后应重新从 1 开始", 1, gate.progress())
        // 重新点满 6 次（含刚才那次）仍然可以触发
        repeat(5) { i -> gate.tap(5_010L + i * 10L) }
        assertTrue(gate.tap(5_100L))
    }

    @Test
    fun gapExactlyAtLimitStillCounts() {
        val gate = DebugTapGate()
        // 恰好等于上限：算连续。边界若写成 >=，用户按上限节奏点就永远凑不满
        val step: Long = DebugTapGate.DEFAULT_MAX_GAP_MS
        repeat(6) { i -> gate.tap(i * step) }
        assertTrue(gate.tap(6 * step))
    }

    @Test
    fun clockGoingBackwardsRestartsInsteadOfUnderflowing() {
        val gate = DebugTapGate()
        repeat(3) { gate.tap(10_000L + it) }
        // 系统时钟被改回过去：now - lastAt 会算成负数，若不特判就永远不会重置
        assertFalse(gate.tap(5_000L))
        assertEquals("时间倒流必须当成一次全新开始", 1, gate.progress())
    }

    @Test
    fun noUnlockHappensWithoutEnoughTaps() {
        val gate = DebugTapGate()
        // 6 次永远不够，无论节奏多快
        repeat(6) { i -> assertFalse(gate.tap(i.toLong())) }
        assertEquals(6, gate.progress())
    }
}
