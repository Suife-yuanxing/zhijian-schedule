package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连点语义：每一拍都定格当前帧、从新胶囊重新起播 */
class RevealRapidSwitchTest {
    private val chip = Rect(left = 60f, top = 366f, right = 314f, bottom = 510f)
    private val chip2 = Rect(left = 616f, top = 222f, right = 870f, bottom = 366f)
    private val bg = Color(0xFFFFFFFF)

    private fun state() = RevealState()

    @Test
    fun `空闲点主题会进入取帧阶段`() {
        val s = state()
        assertTrue(s.requestTheme("amoled", "paper", chip, bg))
        assertEquals("amoled", s.pending?.toId)
        assertNull(s.active)
        // 取帧期间渲染主题不动：跟着设置走会提前换主题，圆就白做了
        assertNull(s.renderedId)
        assertFalse(s.frozen)
    }

    @Test
    fun `取帧期间再点只换目标 不重开一轮`() {
        val s = state()
        s.requestTheme("amoled", "paper", chip, bg)
        assertTrue(s.requestTheme("glass", "paper", chip, bg))
        assertEquals("glass", s.pending?.toId)
        assertNull(s.active)
        // 目标换成自己正在去的主题 → 无意义，不落库
        assertFalse(s.requestTheme("glass", "paper", chip, bg))
    }

    @Test
    fun `扩散中再点会先定格 不立刻换色`() {
        val s = state()
        s.requestTheme("amoled", "paper", chip, bg)
        s.beginReveal(null)
        s.progress.floatValue = 0.4f

        assertTrue(s.requestTheme("glass", "amoled", chip2, bg))

        // 定格：屏幕停在这一帧当图底，主题先不动（否则就成「圆内瞬间换色」了）
        assertTrue(s.frozen)
        assertEquals("amoled", s.active?.toId)
        assertEquals("amoled", s.renderedId)
        assertEquals(0.4f, s.progress.floatValue, 1e-6f)
        assertEquals("glass", s.pending?.toId)
    }

    @Test
    fun `图底到手后从新胶囊重新起播`() {
        val s = state()
        s.requestTheme("amoled", "paper", chip, bg)
        s.beginReveal(null)
        val firstRun = s.active!!.runId
        s.progress.floatValue = 0.4f
        s.requestTheme("glass", "amoled", chip2, bg)

        s.beginReveal(null)

        // 新一段：进度归零、圆心换成刚点的胶囊、runId 换班
        assertEquals(0f, s.progress.floatValue, 1e-6f)
        assertEquals("glass", s.active?.toId)
        assertEquals(chip2.center, s.active!!.center)
        assertTrue(s.active!!.runId > firstRun)
        assertFalse(s.frozen)
        assertNull(s.pending)
        assertEquals("glass", s.renderedId)
    }

    @Test
    fun `换段后旧驱动收尾不会误清新一段`() {
        val s = state()
        s.requestTheme("amoled", "paper", chip, bg)
        s.beginReveal(null)
        val oldRun = s.active!!.runId
        s.requestTheme("glass", "amoled", chip2, bg)
        s.beginReveal(null)
        val newRun = s.active!!.runId

        // 旧协程被取消时的 finally 落在这里：必须认得自己已过期
        s.finishIfRunning(oldRun)
        assertNotNull(s.active)
        assertEquals("glass", s.renderedId)

        s.finishIfRunning(newRun)
        assertNull(s.active)
    }

    @Test
    fun `空闲时跟随设置 播放中不跟随`() {
        val s = state()
        s.onSettingsTheme("paper")
        assertEquals("paper", s.renderedId)

        s.requestTheme("amoled", "paper", chip, bg)
        // 取帧期间设置抢跑也不许换主题
        s.onSettingsTheme("amoled")
        assertEquals("paper", s.renderedId)

        s.beginReveal(null)
        s.onSettingsTheme("glass")
        assertEquals("amoled", s.renderedId)

        s.finish()
        assertEquals("amoled", s.renderedId)
    }

    @Test
    fun `连点后过期的设置回传不会让主题回跳`() {
        val s = state()
        s.requestTheme("amoled", "paper", chip, bg)
        s.requestTheme("glass", "amoled", chip, bg)
        s.beginReveal(null)
        s.finish()

        // 中间那次 amoled 的回传晚到：不许把已经落定的 glass 顶掉
        s.onSettingsTheme("amoled")
        assertEquals("glass", s.renderedId)

        s.onSettingsTheme("glass")
        assertEquals("glass", s.renderedId)
    }
}
