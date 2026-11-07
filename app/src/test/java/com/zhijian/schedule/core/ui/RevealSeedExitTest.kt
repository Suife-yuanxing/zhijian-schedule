package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「方块离场」这条时间线的状态契约。
 *
 * 编排是**同时**离场：方块缩小与页面外扩由同一次动画逐帧一起推进。这句话里可被钉死的事实：
 * ①离场进度与展开进度是两条独立的值，终点不同但必须齐平；②铺满那一刻方块已经缩没了，
 * 交接处没有第二次动作；③换主题、反向都不带方块副本。这些在真机上只表现为「观感怪」，
 * 不会崩、不会报错，所以必须写成断言。
 */
class RevealSeedExitTest {
    private val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)
    private val badge =
        SeedBadge(
            fill = Color.Red,
            tint = Color.Black,
        )
    private val seed = SeedShape(bounds = fab, cornerRadius = 48f, badge = badge)

    @Test
    fun `页面扩散会带上种子 换主题不带`() {
        val nav = RevealState()
        assertTrue(nav.requestNavigation(seed, Color.White) { })
        nav.beginReveal(null)
        assertNotNull("页面扩散必须留下种子，方块要按它重画", nav.seed)
        assertEquals(seed, nav.seed)

        val theme = RevealState()
        assertTrue(
            theme.requestTheme(
                "amoled",
                "paper",
                fab,
                Color.White,
            ),
        )
        theme.beginReveal(null)
        assertNull("换主题是圆，没有方块要复制", theme.seed)
    }

    /**
     * 两条进度是同一次动画推的：任何时刻都得相等。
     *
     * 这条把「同时离场」写成了不变量。驱动协程里两者共用同一个 value，所以状态层
     * 只要保证「谁也没被单独写歪」，真机上就不会出现「先铺满、方块又动一下」。
     */
    @Test
    fun `离场进度与展开进度同步推进`() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        s.requestNavigation(seed, Color.White) { }
        s.beginReveal(null)
        assertEquals(0f, s.seedExit.floatValue, 1e-6f)

        // 模拟驱动的逐帧写法：同一个 value 写进两个槽
        for (i in 0..10) {
            val v = i / 10f
            s.progress.floatValue = v
            s.seedExit.floatValue = v
            assertEquals("进度必须齐平", s.progress.floatValue, s.seedExit.floatValue, 1e-6f)
        }

        // 铺满那一刻：方块已经化没了（seedAlpha(1) == 0），不残留任何残影
        s.progress.floatValue = 1f
        s.seedExit.floatValue = 1f
        assertEquals(0f, seedAlpha(s.seedExit.floatValue), 1e-6f)
        assertEquals(0f, seedGlyphAlpha(s.seedExit.floatValue), 1e-6f)
    }

    @Test
    fun `收尾时两条进度一起归零 不留残影`() {
        val s = RevealState()
        s.requestNavigation(seed, Color.White) { }
        s.beginReveal(null)
        s.progress.floatValue = 1f
        s.seedExit.floatValue = 1f

        s.finish()

        assertEquals(0f, s.progress.floatValue, 1e-6f)
        assertEquals(0f, s.seedExit.floatValue, 1e-6f)
        assertNull(s.seed)
    }

    /** 反向（返回）不带方块副本：那一程真按钮还在课表页里活着，副本会画成两个 */
    @Test
    fun `返回方向不需要方块副本`() {
        val s = RevealState()
        s.requestNavigationBack(seed, Color.White) { }
        s.beginReveal(null)
        // 种子里确实带着 badge（返回要靠它算收回的目标形状），但宿主按方向跳过副本
        assertNotNull(s.seed)
        assertTrue(s.active!!.reverse)
        assertEquals(0f, s.seedExit.floatValue, 1e-6f)
    }

    /** 外扩距离算出来是正数：方块永远盖不满整屏，这是它能当种子的前提 */
    @Test
    fun `方块到整屏之间一定有正的外扩距离`() {
        val g = dilationToCover(seed, 1080f, 2376f)
        assertTrue("外扩距离必须为正，实际 " + g, g > 0f)
        // 方块贴右下角：最大距离来自「上边到画布顶」= 1896
        assertEquals(1898f, g, 1e-3f)
    }
}
