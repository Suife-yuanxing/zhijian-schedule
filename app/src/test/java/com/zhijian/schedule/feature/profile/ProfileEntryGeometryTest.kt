package com.zhijian.schedule.feature.profile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.zhijian.schedule.core.util.lerpRect
import com.zhijian.schedule.core.util.smoothstep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * 入口卡转场的几何与关键帧。
 *
 * 这些是纯函数：进入展开、中途反向收回、返回回收的全部像素都由它们推出。
 * 转场本身只能靠真机肉眼看，所以这里把「端点、单调性、裁剪框与容器的对应关系」钉死——
 * 改关键帧系数时若有端点被破坏，先红在这里，而不是等装机后才发现卡片闪了一下。
 */
class ProfileEntryGeometryTest {
    /** 常见机型的内容区（1080x2376）与一颗入口卡 (100,200)-(300,260) */
    private val content = Size(1080f, 2376f)
    private val card = Rect(left = 100f, top = 200f, right = 300f, bottom = 260f)
    private val cardRadius = 42f

    /** 图层契约：裁剪框先缩放再平移就得到容器 */
    private fun applyTransform(
        clip: Rect,
        scale: Float,
        translationX: Float,
        translationY: Float,
    ): Rect =
        Rect(
            left = clip.left * scale + translationX,
            top = clip.top * scale + translationY,
            right = clip.right * scale + translationX,
            bottom = clip.bottom * scale + translationY,
        )

    // ---------- 缓动与插值 ----------

    @Test
    fun `收拢进度两端钉在 0 与 1`() {
        assertEquals(0f, collapseProgress(0f), 1e-6f)
        assertEquals(1f, collapseProgress(1f), 1e-6f)
    }

    @Test
    fun `收拢进度越界时钳到端点`() {
        assertEquals(0f, collapseProgress(-0.5f), 1e-6f)
        assertEquals(1f, collapseProgress(1.7f), 1e-6f)
    }

    @Test
    fun `收拢进度前段走得比后段多`() {
        // 三次 ease-out：半程已经走完 87.5%，纸面先迅速贴近卡片轮廓再软着陆
        assertEquals(0.875f, collapseProgress(0.5f), 1e-6f)
        assertTrue(collapseProgress(0.25f) > 0.25f)
        assertTrue(collapseProgress(0.75f) > collapseProgress(0.5f))
    }

    @Test
    fun `收拢进度全程单调不减`() {
        var previous = -1f
        var p = 0f
        while (p <= 1.0001f) {
            val current = collapseProgress(p)
            assertTrue("p=$p", current >= previous)
            previous = current
            p += 0.02f
        }
    }

    @Test
    fun `smoothstep 端点与越界钳位`() {
        assertEquals(0f, smoothstep(0f, 1f, 0f), 1e-6f)
        assertEquals(1f, smoothstep(0f, 1f, 1f), 1e-6f)
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertEquals(0f, smoothstep(0.2f, 0.4f, 0.1f), 1e-6f)
        assertEquals(1f, smoothstep(0.2f, 0.4f, 0.9f), 1e-6f)
    }

    @Test
    fun `smoothstep 起止相等时不除零`() {
        assertEquals(0f, smoothstep(0.5f, 0.5f, 0.4f), 1e-6f)
        assertEquals(1f, smoothstep(0.5f, 0.5f, 0.6f), 1e-6f)
    }

    @Test
    fun `lerpRect 与两端重合`() {
        val target = Rect(Offset.Zero, content)
        assertEquals(card, lerpRect(card, target, 0f))
        assertEquals(target, lerpRect(card, target, 1f))
        val half = lerpRect(card, target, 0.5f)
        assertEquals((card.left + target.left) / 2f, half.left, 1e-4f)
        assertEquals((card.bottom + target.bottom) / 2f, half.bottom, 1e-4f)
    }

    // ---------- 进入展开 ----------

    @Test
    fun `展开容器从入口卡长到整页`() {
        assertEquals(card, entryForwardContainer(card, content, 0f))
        assertEquals(Rect(Offset.Zero, content), entryForwardContainer(card, content, 1f))
    }

    @Test
    fun `展开圆角从卡片圆角收到 0 且不超过短边一半`() {
        // 起手容器就是入口卡本身（right-left=200、bottom-top=60），短边一半 = 30，
        // 所以 42 的卡片圆角在这里必须先被夹到 30，否则 60 高的圆角矩形会自交
        val startRadius = min(cardRadius, card.height / 2f)
        assertEquals(startRadius, entryForwardRadius(card, content, 0f, cardRadius = cardRadius), 1e-4f)
        assertEquals(0f, entryForwardRadius(card, content, 1f, cardRadius = cardRadius), 1e-4f)
        // 起手容器很扁（60 高）：圆角必须收回短边一半以内，否则圆角矩形会自交
        val clamped = entryForwardRadius(card, content, 0f, cardRadius = 400f)
        assertEquals(30f, clamped, 1e-4f)
    }

    @Test
    fun `展开图层在起点把入口卡映射成裁剪框`() {
        val transform = entryCoverTransform(card, content, 0f, cardRadius)
        val mapped =
            applyTransform(
                transform.clipRect,
                transform.scale,
                transform.translationX,
                transform.translationY,
            )
        assertRectEquals(card, mapped, 1e-2f)
        assertTrue(transform.scale > 0f)
        // 裁剪框必须落在页面内容里，否则展开首帧会露出空白
        assertInsideContent(transform.clipRect)
    }

    @Test
    fun `展开图层在终点是恒等变换`() {
        val transform = entryCoverTransform(card, content, 1f, cardRadius)
        assertEquals(1f, transform.scale, 1e-4f)
        assertEquals(0f, transform.translationX, 1e-4f)
        assertEquals(0f, transform.translationY, 1e-4f)
        assertEquals(0f, transform.radius, 1e-4f)
        assertRectEquals(Rect(Offset.Zero, content), transform.clipRect, 1e-3f)
    }

    @Test
    fun `展开全程裁剪框都落在页面内容里且圆角合法`() {
        var p = 0f
        while (p <= 1.0001f) {
            val transform = entryCoverTransform(card, content, p, cardRadius)
            assertInsideContent(transform.clipRect, tolerance = 1e-2f, progress = p)
            assertTrue(
                "p=$p radius=${transform.radius}",
                transform.radius >= 0f &&
                    transform.radius <=
                    minOf(transform.clipRect.width, transform.clipRect.height) / 2f + 1e-3f,
            )
            // 容器中心与内容中心对齐：内容始终盖住裁剪框，不会在某一侧露出空白
            val mapped =
                applyTransform(
                    transform.clipRect,
                    transform.scale,
                    transform.translationX,
                    transform.translationY,
                )
            val container = entryForwardContainer(card, content, p)
            assertEquals(container.center.x, mapped.center.x, 1e-2f)
            assertEquals(container.center.y, mapped.center.y, 1e-2f)
            p += 0.1f
        }
    }

    @Test
    fun `只有收回末段才淡出内容`() {
        assertEquals(1f, cardHandOffAlpha(0.5f, fadeToCard = false), 1e-6f)
        assertEquals(0f, cardHandOffAlpha(0f, fadeToCard = true), 1e-6f)
        assertEquals(1f, cardHandOffAlpha(0.12f, fadeToCard = true), 1e-6f)
        assertTrue(cardHandOffAlpha(0.06f, fadeToCard = true) in 0f..1f)
    }

    // ---------- 返回回收 ----------

    @Test
    fun `回收起点就是整页`() {
        val full = Rect(Offset.Zero, content)
        val target = entryExitFallbackTarget(content)
        val frame = entryCollapseFrame(full, target, 0f, cardRadius)
        assertRectEquals(full, frame.container, 1e-3f)
        assertEquals(0f, frame.radius, 1e-4f)
        assertEquals(1f, frame.contentAlpha, 1e-4f)
        assertEquals(1f, frame.fillAlpha, 1e-4f)
        // 起手不画描边：快照还盖满全屏，多一圈线会闪
        assertEquals(0f, frame.borderAlpha, 1e-6f)
        assertEquals(0f, frame.fillMix, 1e-6f)
    }

    @Test
    fun `回收终点收进入口卡`() {
        val full = Rect(Offset.Zero, content)
        val target = entryExitFallbackTarget(content)
        val frame = entryCollapseFrame(full, target, 1f, cardRadius)
        assertRectEquals(target, frame.container, 1e-3f)
        assertEquals(cardRadius, frame.radius, 1e-3f)
        assertEquals(0f, frame.contentAlpha, 1e-6f)
        assertEquals(0f, frame.fillAlpha, 1e-6f)
        assertEquals(0f, frame.borderAlpha, 1e-6f)
        assertEquals(1f, frame.fillMix, 1e-4f)
    }

    @Test
    fun `回收全程容器单调收缩`() {
        val full = Rect(Offset.Zero, content)
        val target = Rect(100f, 1378f, 980f, 1520f)
        var lastW = Float.MAX_VALUE
        var lastH = Float.MAX_VALUE
        var p = 0f
        while (p <= 1.0001f) {
            val frame = entryCollapseFrame(full, target, p, cardRadius)
            assertTrue("p=$p w=${frame.container.width}", frame.container.width <= lastW + 1e-3f)
            assertTrue("p=$p h=${frame.container.height}", frame.container.height <= lastH + 1e-3f)
            lastW = frame.container.width
            lastH = frame.container.height
            // 快照始终以容器中心为中心缩放，回收途中最远边不会甩出容器
            assertEquals(
                frame.container.center.x,
                frame.translationX + full.width * frame.coverScale / 2f,
                1e-2f,
            )
            assertEquals(
                frame.container.center.y,
                frame.translationY + full.height * frame.coverScale / 2f,
                1e-2f,
            )
            p += 0.05f
        }
    }

    @Test
    fun `回收前段已经走完大半路程`() {
        val full = Rect(Offset.Zero, content)
        val target = Rect(100f, 1378f, 980f, 1520f)
        val quarter = entryCollapseFrame(full, target, 0.25f, cardRadius)
        val half = entryCollapseFrame(full, target, 0.5f, cardRadius)
        val remainingAtQuarter = quarter.container.width - target.width
        val remainingAtHalf = half.container.width - target.width
        assertTrue(remainingAtHalf < remainingAtQuarter)
        assertTrue(remainingAtHalf < (full.width - target.width) * 0.2f)
    }

    // ---------- 边界与兜底 ----------

    @Test
    fun `录制层未出图时退回画布尺寸`() {
        assertEquals(Rect(0f, 0f, 1080f, 2376f), entryCaptureBounds(0, 0, content))
        assertEquals(Rect(0f, 0f, 1080f, 1200f), entryCaptureBounds(0, 1200, content))
        assertEquals(Rect(0f, 0f, 720f, 1200f), entryCaptureBounds(720, 1200, content))
    }

    @Test
    fun `兜底落点在画布下部居中`() {
        val target = entryExitFallbackTarget(content)
        assertEquals(108f, target.left, 1e-4f)
        assertEquals(972f, target.right, 1e-4f)
        assertEquals(540f, target.center.x, 1e-4f)
        assertTrue(target.top > content.height / 2f)
        assertTrue(target.bottom < content.height)
    }

    private fun assertInsideContent(
        rect: Rect,
        tolerance: Float = 0f,
        progress: Float = 0f,
    ) {
        assertTrue(
            "p=$progress clip=$rect",
            rect.left >= -tolerance &&
                rect.top >= -tolerance &&
                rect.right <= content.width + tolerance &&
                rect.bottom <= content.height + tolerance,
        )
    }

    private fun assertRectEquals(
        expected: Rect,
        actual: Rect,
        tolerance: Float,
    ) {
        assertEquals("left", expected.left, actual.left, tolerance)
        assertEquals("top", expected.top, actual.top, tolerance)
        assertEquals("right", expected.right, actual.right, tolerance)
        assertEquals("bottom", expected.bottom, actual.bottom, tolerance)
    }
}
