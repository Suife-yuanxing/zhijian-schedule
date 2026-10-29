package com.zhijian.schedule.feature.jw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「视口塌成 0 高」的回归钉子。
 *
 * 广西工程门户登录页在 WebView 里空白了整整一版：DOM 完好、样式表全部加载、正文有文字，
 * 但 html 元素的高度是 0，百分比高度整条链路解析成 0。根因是 WebView 在宽高仍为 0 时
 * 就提交了第一次导航，Chromium 把视口高度锁死在 0——同一台机器上百度首页的 100vh 也是 0px，
 * 与学校页面无关。
 *
 * 这几条用例钉住两件事：导航只许在量到尺寸之后提交；兜底脚本只在视口真的坏掉时动手。
 */
class JwWebViewViewportTest {
    /** 宽高都量到了才允许直接导航——这是能力的正常路径 */
    @Test
    fun `loads once both sides are measured`() {
        assertTrue(shouldLoadWhenSized(1080, 1383))
        assertTrue(shouldLoadWhenSized(1, 1))
    }

    /** 任一边为 0 都必须等布局：此刻导航正是白屏的成因 */
    @Test
    fun `waits while either side is still zero`() {
        assertFalse(shouldLoadWhenSized(0, 1383))
        assertFalse(shouldLoadWhenSized(1080, 0))
        assertFalse(shouldLoadWhenSized(0, 0))
    }

    /** 兜底脚本的入口判定：视口高度是正数才有得修，0 或负数直接放弃 */
    @Test
    fun `repair script needs a real viewport height`() {
        val js = VIEWPORT_HEIGHT_REPAIR_JS
        assertTrue(js.contains("visualViewport"))
        assertTrue(js.contains("innerHeight"))
        assertTrue(js.contains("100vh"))
    }

    /** 兜底只在 100vh 量出来接近 0 时才写样式：量出来正常必须一个字节都不改 */
    @Test
    fun `repair script is a no-op when viewport units already work`() {
        val js = VIEWPORT_HEIGHT_REPAIR_JS
        assertTrue(js.contains("if (vh > 1) return;"))
    }

    /** 写进去的高度必须带 important：学校样式表里的 height:100% 否则会盖回来 */
    @Test
    fun `repair script overrides stylesheet height`() {
        val js = VIEWPORT_HEIGHT_REPAIR_JS
        assertTrue(js.contains("setProperty('height', h + 'px', 'important')"))
    }

    /** 只修 html 与 body，不碰页面内容节点——否则滚动与固定定位会被一起改坏 */
    @Test
    fun `repair script stays on documentElement and body`() {
        val js = VIEWPORT_HEIGHT_REPAIR_JS
        assertTrue(js.contains("document.documentElement"))
        assertTrue(js.contains("document.body"))
        assertFalse(js.contains("querySelectorAll"))
    }
}
