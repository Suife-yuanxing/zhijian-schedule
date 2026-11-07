package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面扩散（加号 → 课程编辑页）的状态机语义 */
class RevealNavigationTest {
    private val fab = Rect(left = 960f, top = 2120f, right = 1060f, bottom = 2220f)
    private val seed = SeedShape(fab, 50f)
    private val bg = Color(0xFFFFFFFF)

    @Test
    fun `页面扩散不改渲染主题 只有圆心和圆`() {
        val s = RevealState()
        assertTrue(s.requestNavigation(seed, bg) { })
        assertNull(s.active)
        assertNull(s.pending?.toId)
        // 渲染主题完全不参与：进来之前是什么就还是什么
        assertNull(s.renderedId)
        assertEquals(RevealShape.Seed(seed), s.pending?.shapeInRoot)
    }

    @Test
    fun `导航在取到旧帧之后才执行`() {
        val s = RevealState()
        var navigated = 0
        s.requestNavigation(seed, bg) { navigated++ }
        // 取帧期间绝不能跳：否则旧帧拍到的就是新页面，圆外的底会提前变
        assertEquals(0, navigated)

        s.beginReveal(null)

        assertEquals(1, navigated)
        assertTrue(s.navigating)
        assertNull(s.active?.toId)
    }

    @Test
    fun `转场进行中不再受理页面扩散`() {
        val s = RevealState()
        s.requestTheme("amoled", "paper", fab, bg)
        assertFalse(s.requestNavigation(seed, bg) { })
    }

    @Test
    fun `页面扩散收尾后恢复跟随设置`() {
        val s = RevealState()
        s.requestNavigation(seed, bg) { }
        s.beginReveal(null)
        assertTrue(s.navigating)

        s.finish()

        assertFalse(s.navigating)
        assertNull(s.active)
        // 主题没被页面扩散碰过：仍由设置决定
        assertNull(s.renderedId)
        s.onSettingsTheme("paper")
        assertEquals("paper", s.renderedId)
    }

    @Test
    fun `主题扩散与页面扩散可交替使用`() {
        val s = RevealState()
        s.requestTheme("amoled", "paper", fab, bg)
        s.beginReveal(null)
        assertEquals("amoled", s.renderedId)
        assertFalse(s.navigating)

        s.finish()
        assertTrue(s.requestNavigation(seed, bg) { })
        s.beginReveal(null)
        // 页面扩散期间渲染主题保持上一次的结果，不能回退成设置里的旧值
        assertEquals("amoled", s.renderedId)
        assertTrue(s.navigating)
    }

    @Test
    fun `返回扩散先取帧再 pop`() {
        val s = RevealState()
        var popped = 0
        s.requestNavigationBack(seed, bg) { popped++ }
        // 取帧之前绝不能 pop：否则拍到的就是上一页，收走的画面就错了
        assertEquals(0, popped)

        s.beginReveal(null)

        assertEquals(1, popped)
        assertTrue(s.navigating)
        assertTrue(s.active!!.reverse)
    }

    @Test
    fun `返回扩散的外扩是反着走的`() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        s.requestNavigationBack(seed, bg) { }
        s.beginReveal(null)
        val active = s.active!!

        s.progress.floatValue = 0f
        val start = s.dilationOf(active)
        s.progress.floatValue = 1f
        val end = s.dilationOf(active)

        // 起手必须盖满整屏（原页面完整可见），收尾正好是控件本身（外扩归零）
        assertTrue("start=" + start + " 应覆盖整屏", start >= 2000f)
        assertEquals(0f, end, 0.01f)
        assertTrue(end < start)
    }

    /** 换主题仍走圆形口径：那条路径这次完全没有改动，用一条断言把它钉住 */
    @Test
    fun `换主题的半径仍从胶囊长到最远角`() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        s.requestTheme("amoled", "paper", fab, bg)
        s.beginReveal(null)
        val active = s.active!!

        s.progress.floatValue = 0f
        assertEquals(50f, s.radiusOf(active), 0.01f)
        s.progress.floatValue = 1f
        assertTrue("终点半径应覆盖整屏", s.radiusOf(active) >= 2000f)
        // 圆的形状不能污染种子：换主题没有方块要复制
        assertNull(s.seed)
    }

    @Test
    fun `转场进行中不再受理返回扩散`() {
        val s = RevealState()
        s.requestNavigation(seed, bg) { }
        assertFalse(s.requestNavigationBack(seed, bg) { })
    }
}
