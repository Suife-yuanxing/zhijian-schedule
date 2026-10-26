package com.zhijian.schedule.feature.profile

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口卡转场的共享状态。
 *
 * 这块状态是「入口卡上报坐标、二级页与回收浮层取坐标」的唯一交汇点：
 * 坐标系一旦换错，回收动画就会落在错误的位置（曾经表现为收进屏幕左上角）。
 */
class ProfileEntryTransitionsTest {

    /** 内容区在窗口中的位置：顶部被状态栏占掉 120px */
    private val contentInWindow = Rect(left = 0f, top = 120f, right = 1080f, bottom = 2376f)

    @Test
    fun `未上报过的入口卡没有落点`() {
        val transitions = ProfileEntryTransitions()
        assertNull(transitions.cardBounds(ProfileEntryKeys.IMPORT))
    }

    @Test
    fun `入口卡坐标从窗口坐标系换算到内容坐标系`() {
        val transitions = ProfileEntryTransitions()
        transitions.updateContentBounds(contentInWindow)
        // 窗口坐标里的卡片：内容区整体下移了 120px
        transitions.updateCardBounds(
            ProfileEntryKeys.GRADES,
            Rect(left = 100f, top = 420f, right = 300f, bottom = 480f),
        )
        val local = transitions.cardBounds(ProfileEntryKeys.GRADES)!!
        assertEquals(100f, local.left, 1e-4f)
        assertEquals(300f, local.top, 1e-4f)
        assertEquals(300f, local.right, 1e-4f)
        assertEquals(360f, local.bottom, 1e-4f)
    }

    @Test
    fun `每颗入口卡各自记自己的落点`() {
        val transitions = ProfileEntryTransitions()
        transitions.updateContentBounds(contentInWindow)
        transitions.updateCardBounds(
            ProfileEntryKeys.IMPORT,
            Rect(left = 0f, top = 120f, right = 200f, bottom = 180f),
        )
        transitions.updateCardBounds(
            ProfileEntryKeys.EXAMS,
            Rect(left = 0f, top = 600f, right = 1080f, bottom = 660f),
        )
        assertEquals(0f, transitions.cardBounds(ProfileEntryKeys.IMPORT)!!.top, 1e-4f)
        assertEquals(480f, transitions.cardBounds(ProfileEntryKeys.EXAMS)!!.top, 1e-4f)
        assertNull(transitions.cardBounds(ProfileEntryKeys.SETTINGS))
    }

    @Test
    fun `返回期间只受理一次回收`() {
        val transitions = ProfileEntryTransitions()
        assertNull(transitions.exitKey)
        transitions.beginExit(ProfileEntryKeys.IMPORT)
        val serial = transitions.exitSerial
        assertEquals(ProfileEntryKeys.IMPORT, transitions.exitKey)
        // 回收进行中再点一次：不换 key、不换代号，浮层继续播当前这段
        transitions.beginExit(ProfileEntryKeys.EXAMS)
        assertEquals(ProfileEntryKeys.IMPORT, transitions.exitKey)
        assertEquals(serial, transitions.exitSerial)
    }

    @Test
    fun `过期的回收代号不能清掉新一轮的浮层`() {
        val transitions = ProfileEntryTransitions()
        transitions.beginExit(ProfileEntryKeys.IMPORT)
        val stale = transitions.exitSerial
        transitions.finishExit(stale)
        assertNull(transitions.exitKey)

        // 第二轮：上一轮的回调即使晚到，也不能把这一轮的浮层关掉
        transitions.beginExit(ProfileEntryKeys.GRADES)
        val current = transitions.exitSerial
        assertTrue(current > stale)
        transitions.finishExit(stale)
        assertEquals(ProfileEntryKeys.GRADES, transitions.exitKey)
        transitions.finishExit(current)
        assertNull(transitions.exitKey)
    }

    @Test
    fun `保持窗口覆盖进入与中途返回两段动画`() {
        // 「我的」页在进入动画与反向收回期间都不能被销毁，否则中途返回会露出空白
        assertTrue(PROFILE_ENTRY_HOLD_MS > PROFILE_ENTRY_ENTER_MS + PROFILE_ENTRY_ABORT_MS)
    }

    @Test
    fun `中途返回的反向时长短于正向进入`() {
        assertTrue(PROFILE_ENTRY_ABORT_MIN_MS < PROFILE_ENTRY_ABORT_MS)
        assertTrue(PROFILE_ENTRY_ABORT_MS < PROFILE_ENTRY_ENTER_MS)
        assertTrue(PROFILE_ENTRY_EXIT_MS < PROFILE_ENTRY_ENTER_MS)
    }
}
