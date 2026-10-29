package com.zhijian.schedule.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向（返回）收回的交接契约。
 *
 * 返回时的背景**必须**是浮层下面的实时内容，不能再是「pop 之后另抓的一张整屏位图」：
 * 那张位图抓完即定格，而课表返回时会重播逐块入场（WeekGrid 的 entranceDelayMs →
 * CourseBlock.cascadeIn），于是整段收回盖着的都是过期画面，动画一结束撤掉浮层就硬切一下——
 * 返回闪烁的直接来源。这组测试守住这条线，不依赖真机观感。
 */
class RevealReverseHandoffTest {
    private val fab = Rect(left = 876f, top = 1896f, right = 1020f, bottom = 2040f)
    private val bg = Color(0xFFFFFFFF)

    @Test
    fun `反向在 beginReveal 之后立刻可播 不再等第二张底图`() {
        val s = RevealState()
        s.hostSize = IntSize(1080, 2376)
        var popped = 0
        assertTrue(s.requestNavigationBack(fab, bg) { popped++ })
        // 取帧阶段：pop 还没跑，也没有 active
        assertEquals(0, popped)
        assertNull(s.active)

        s.beginReveal(null)

        // 一步到位：pop 已执行、反向量就位、进度归零、没有 pending，
        // 不存在「等上一页底图」的第二阶段（旧实现在这里等 6 帧，且那 6 帧里驱动已经在跑）
        assertEquals(1, popped)
        assertTrue(s.active!!.reverse)
        assertEquals(0f, s.progress.floatValue, 1e-6f)
        assertNull(s.pending)
    }

    @Test
    fun `状态里只有一张位图 反向背景交给实时内容`() {
        // 结构性契约：反向只需要 oldFrame（被收走的那一页）这一张位图。
        // 一旦再出现第二张整屏位图，就是「过期底图硬盖实时课表」的老毛病复发。
        // 认法：by mutableStateOf 的属性在 JVM 上没有 ImageBitmap 字段（委托字段的泛型被擦除），
        // 但读取器 getXxx() 的返回类型是具体的，按它数才准。
        val bitmapGetters =
            RevealState::class.java.declaredMethods.filter {
                it.parameterCount == 0 && ImageBitmap::class.java.isAssignableFrom(it.returnType)
            }
        assertEquals(
            "反向背景必须用实时内容：预期只有 oldFrame 一张位图，实际 ${
                bitmapGetters.map { it.name }
            }",
            1,
            bitmapGetters.size,
        )
        assertTrue(
            "oldFrame 的读取器没找到，这条契约就形同虚设：${bitmapGetters.first().name}",
            bitmapGetters.first().name.contains("oldFrame", ignoreCase = true),
        )
    }

    @Test
    fun `反向遮罩在收尾前不透明 收尾一刻完全化掉`() {
        // 圆收到加号大小之前整块实心（此时圆外是实时课表，靠裁剪露出来）
        assertEquals(1f, reverseMaskAlpha(0f), 1e-6f)
        assertEquals(1f, reverseMaskAlpha(0.5f), 1e-6f)
        assertEquals(1f, reverseMaskAlpha(0.9f), 1e-6f)
        // 驱动跑到进度 1 时遮罩必须已经归零：那一刻撤掉浮层，画面上不能剩任何旧页残影
        assertEquals(0f, reverseMaskAlpha(1f), 1e-6f)
        assertTrue(reverseMaskAlpha(1f) < reverseMaskAlpha(0.99f))

        var prev = Float.MAX_VALUE
        var i = 0
        while (i <= 100) {
            val t = i / 100f
            val a = reverseMaskAlpha(t)
            assertTrue("t=$t alpha=$a 应单调不增", a <= prev + 1e-6f)
            prev = a
            i++
        }
    }

    /**
     * 反向遮罩的淡出只发生在最后 10% 的**进度**里，这一段在镜像曲线下占总时长的
     * 1 - 0.786 = 21.4%，即 370 × 0.214 ≈ 79ms：短到读不出一层淡出，又足够消掉硬边圆点。
     */
    @Test
    fun `淡出段落在最后两成时间内`() {
        val duration = Motion.DUR_REVEAL_BACK
        val fadeStart = 0.786f
        val fadeMillis = duration * (1f - fadeStart)
        assertTrue("淡出 ${fadeMillis}ms 不该拖成一次独立的淡出", fadeMillis < 120f)
        assertTrue("淡出也不该短到看不见", fadeMillis > 40f)
        // 镜像曲线在 78.6% 时间点走到 90% 的半径行程——与上面的淡出起点是同一个位置
        assertEquals(0.9f, Motion.EaseRevealDecelerate.transform(fadeStart), 0.01f)
    }
}
