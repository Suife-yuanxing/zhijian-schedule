package com.zhijian.schedule.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板入场节拍：次序、边界与「不会卡在中间」这三件事。
 *
 * 为什么值得单测：这些常量错一位不会崩、也不会报错，只会让入场看起来「一起冒出来」或者
 * 「最后一块半天不来」。这类问题在真机上要靠逐帧对比才看得出来，用断言钉住最省事。
 */
class SheetChoreographyTest {
    @Test
    fun slotsAreStrictlyOrdered() {
        val order =
            listOf(
                SheetChoreography.HERO,
                SheetChoreography.CHIP,
                SheetChoreography.TITLE,
                SheetChoreography.PILL,
                SheetChoreography.DETAILS,
                SheetChoreography.ROW,
                SheetChoreography.ACTIONS,
            )
        order.zipWithNext { a, b ->
            assertTrue("槽位必须严格递增：$a -> $b", b > a)
        }
    }

    @Test
    fun secondPillComesAfterFirst() {
        val secondPill = SheetChoreography.PILL + SheetChoreography.PILL_STEP
        val gap = SheetChoreography.startOf(secondPill) - SheetChoreography.startOf(SheetChoreography.PILL)
        assertTrue("两枚 pill 必须错开，但幅度要小于一整拍", gap > 0f && gap < SheetChoreography.STEP_MS)
    }

    @Test
    fun lastActionStartsAfterAllRows() {
        // 三行信息是最常见的情形；按钮必须排在三行之后
        val lastRow = SheetChoreography.ROW + SheetChoreography.ROW_STEP * 2
        assertTrue(lastRow < SheetChoreography.ACTIONS)
    }

    @Test
    fun timelineCoversLastSlot() {
        val total = SheetChoreography.totalMs(SheetChoreography.ACTIONS).toFloat()
        assertEquals(1f, SheetChoreography.progressAt(total, SheetChoreography.ACTIONS), 1e-4f)
        assertTrue(
            "时间轴必须在最后一拍开始后才结束",
            total >= SheetChoreography.startOf(SheetChoreography.ACTIONS) + SheetChoreography.SLOT_MS,
        )
    }

    @Test
    fun progressIsClampedAtBothEnds() {
        assertEquals(0f, SheetChoreography.progressAt(0f, SheetChoreography.HERO), 0f)
        assertEquals(1f, SheetChoreography.progressAt(10_000f, SheetChoreography.HERO), 0f)
        // 还没轮到的槽位必须严格停在 0：否则面板一打开就有一半内容提前现身，错峰失效
        assertEquals(0f, SheetChoreography.progressAt(0f, SheetChoreography.ACTIONS), 0f)
    }

    @Test
    fun progressIsMonotonic() {
        var previous = -1f
        var t = 0f
        while (t <= 800f) {
            val p = SheetChoreography.progressAt(t, SheetChoreography.DETAILS)
            assertTrue("进度不得回退（t=$t）", p >= previous)
            previous = p
            t += 8f
        }
    }

    @Test
    fun bounceReturnsToExactTarget() {
        // 两端必须精确等于 0 / 1：停在过冲位置是「元素永远比设计稿大一圈」的直接原因
        assertEquals(0f, SheetChoreography.bounceAt(0f, 0.35f), 0f)
        assertEquals(1f, SheetChoreography.bounceAt(1f, 0.35f), 0f)
        assertTrue("中途必须冲过头", SheetChoreography.bounceAt(0.5f, 0.35f) > 0.5f)
    }

    @Test
    fun noBounceMeansIdentity() {
        for (i in 0..20) {
            val p = i / 20f
            assertEquals(p, SheetChoreography.bounceAt(p, 0f), 1e-6f)
        }
    }

    @Test
    fun alphaNeverExceedsOneAndLeadsGeometryForBigBlocks() {
        for (i in 0..40) {
            val t = i * 10f
            val a = SheetChoreography.alphaAt(t, SheetChoreography.HERO, EnterSpec.Hero)
            assertTrue("alpha 必须在 0..1（t=$t, a=$a）", a in 0f..1f)
        }
        // 大块（alphaPower > 1）在入场中段应当比几何进度更透明：先虚后实
        val mid = SheetChoreography.startOf(SheetChoreography.HERO) + SheetChoreography.SLOT_MS * 0.5f
        val geometry = SheetChoreography.progressAt(mid, SheetChoreography.HERO)
        val alpha = SheetChoreography.alphaAt(mid, SheetChoreography.HERO, EnterSpec.Hero)
        assertTrue("头卡应当先虚后实", alpha < geometry)
    }

    @Test
    fun actionsSlotMovesEarlierWithoutDetails() {
        // 没有信息卡时按钮必须提前：否则面板先停住、按钮才冒出来，读作「卡了一下」
        assertTrue(SheetChoreography.actionsSlot(hasDetails = false) < SheetChoreography.ACTIONS)
        assertEquals(SheetChoreography.ACTIONS, SheetChoreography.actionsSlot(hasDetails = true), 0f)
    }

    @Test
    fun heroDoesNotRise() {
        // 头卡必须读作「贴在面板上长出来」，上浮会让它读成「从下面飞进来」
        assertEquals(0f, EnterSpec.Hero.riseDp, 0f)
        assertEquals(0f, EnterSpec.Chip.riseDp, 0f)
    }
}
