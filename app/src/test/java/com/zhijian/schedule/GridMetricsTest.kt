package com.zhijian.schedule

import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.feature.schedule.components.GridLayout
import com.zhijian.schedule.feature.schedule.components.GridMetrics
import com.zhijian.schedule.feature.schedule.components.blockContentFor
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格几何与课块内容预算。
 *
 * 这些是纯函数：字体倍率一变，行高、时段头、时间轴宽度、块内行数全跟着它算，
 * 所以先把 1.0 倍的行为钉死，再验证放大后不会裁字。
 */
class GridMetricsTest {

    private val times = listOf(
        "08:20" to "09:05", "09:15" to "10:00", "10:10" to "10:55", "11:05" to "11:45",
        "14:20" to "15:05", "15:15" to "16:00", "16:05" to "16:50", "17:00" to "17:40",
        "18:20" to "19:05", "19:15" to "20:00", "20:10" to "20:55", "21:05" to "21:35",
    )

    private fun sections(): List<SectionTimeEntity> = times.mapIndexed { i, (start, end) ->
        SectionTimeEntity(
            modeId = 1L,
            sectionNo = i + 1,
            periodNo = (i + 2) / 2,
            session = if (i < 4) "AM" else "PM",
            startTime = start,
            endTime = end,
        )
    }

    // --- metrics ---

    @Test
    fun `fontScale 1 keeps the baseline pixel sizes`() {
        val m = GridMetrics.of(1f)
        assertEquals(1f, m.fontScale, 1e-4f)
        assertEquals(60f, m.timeAxisWidth.value, 1e-3f)
        assertEquals(56f, m.headerHeight.value, 1e-3f)
        assertEquals(46f, m.sectionHeight.value, 1e-3f)
        assertEquals(24f, m.bandHeadHeight.value, 1e-3f)
        assertEquals(12f, m.bandGap.value, 1e-3f)
        assertEquals(1f, m.boxScale, 1e-4f)
    }

    @Test
    fun `fontScale 1_3 scales the grid proportionally`() {
        val m = GridMetrics.of(1.3f)
        assertEquals(1.3f, m.fontScale, 1e-4f)
        assertEquals(46f * 1.3f, m.sectionHeight.value, 1e-3f)
        assertEquals(60f * 1.3f, m.timeAxisWidth.value, 1e-3f)
        assertEquals(1.3f, m.boxScale, 1e-4f)
    }

    @Test
    fun `fontScale 2 is capped at 1_5 so columns keep more than one glyph`() {
        val m = GridMetrics.of(2f)
        assertEquals(1.5f, m.fontScale, 1e-4f)
        assertEquals(46f * 1.5f, m.sectionHeight.value, 1e-3f)
        // 时间轴另有 1.4 倍上限，否则天列会被挤窄
        assertEquals(60f * 1.4f, m.timeAxisWidth.value, 1e-3f)
    }

    @Test
    fun `small font scale shrinks instead of being clamped up`() {
        val m = GridMetrics.of(0.85f)
        assertEquals(0.85f, m.fontScale, 1e-4f)
        assertEquals(46f * 0.85f, m.sectionHeight.value, 1e-3f)
    }

    @Test
    fun `invalid font scale falls back to 1`() {
        assertEquals(1f, GridMetrics.of(0f).fontScale, 1e-4f)
        assertEquals(1f, GridMetrics.of(Float.NaN).fontScale, 1e-4f)
        assertEquals(1f, GridMetrics.of(-2f).fontScale, 1e-4f)
    }

    // --- 课块内容预算：1.0 倍必须和改版前完全一致 ---

    @Test
    fun `single section block only holds the title`() {
        val c = blockContentFor(1, GridMetrics.Default)
        assertEquals(2, c.titleLines)
        assertFalse(c.showRoom)
        assertFalse(c.showTeacher)
        assertFalse(c.showFooter)
    }

    @Test
    fun `two section block adds room and week footer`() {
        val c = blockContentFor(2, GridMetrics.Default)
        assertEquals(3, c.titleLines)
        assertTrue(c.showRoom)
        assertFalse(c.showTeacher)
        assertTrue(c.showFooter)
    }

    @Test
    fun `three section block also fits the teacher`() {
        val c = blockContentFor(3, GridMetrics.Default)
        assertEquals(4, c.titleLines)
        assertTrue(c.showRoom)
        assertTrue(c.showTeacher)
        assertTrue(c.showFooter)
    }

    @Test
    fun `huge font scale still leaves at least one title line and drops the rest`() {
        // 容器封顶在 1.5 倍，但文字可以到 3 倍：行数收敛而不是溢出裁字
        val c = blockContentFor(1, GridMetrics.of(3f))
        assertTrue(c.titleLines >= 1)
        assertFalse(c.showRoom)
        assertFalse(c.showFooter)
    }

    // --- 纵向几何 ---

    @Test
    fun `twelve sections split into three bands`() {
        val layout = GridLayout(sections())
        assertEquals(listOf("上午", "下午", "晚上"), layout.bands.map { it.label })
        assertEquals(listOf(4, 4, 4), layout.bands.map { it.count })
        assertEquals(listOf(0, 4, 8), layout.bands.map { it.startIndex })
        // 3 个时段头 + 12 节 + 2 段间距
        assertEquals(3 * 24f + 12 * 46f + 2 * 12f, layout.totalHeight.value, 1e-3f)
    }

    @Test
    fun `section tops are continuous inside a band and skip the band gap`() {
        val layout = GridLayout(sections())
        assertEquals(24f, layout.sectionTop(0).value, 1e-3f)
        for (i in 1 until 4) {
            assertEquals(46f, (layout.sectionTop(i) - layout.sectionTop(i - 1)).value, 1e-3f)
        }
        // 跨时段：多出「下一个时段头 24dp」+ 段间距 12dp
        val gapBetweenBands = 24f + 12f
        assertEquals(46f + gapBetweenBands, (layout.sectionTop(4) - layout.sectionTop(3)).value, 1e-3f)
    }

    @Test
    fun `sectionAt maps back to the section under the finger`() {
        val layout = GridLayout(sections())
        for (i in 0 until 12) {
            assertEquals(i, layout.sectionAt(layout.sectionTop(i) + 1.dp))
        }
        // 越界坐标钳到最后一节，点击不崩
        assertEquals(11, layout.sectionAt(layout.totalHeight + 400.dp))
    }

    @Test
    fun `time slot interpolates inside a class`() {
        val layout = GridLayout(sections())
        val slot = layout.timeSlot(LocalTime.of(9, 30))!!
        assertTrue(slot.inClass)
        // 第二节 09:15–10:00 共 45 分钟，9:30 落在三分之一
        val expected = layout.sectionTop(1) + 46.dp * (15f / 45f)
        assertEquals(expected.value, slot.y.value, 1e-2f)
    }

    @Test
    fun `time slot parks at the next section top during a break`() {
        val layout = GridLayout(sections())
        val slot = layout.timeSlot(LocalTime.of(9, 10))!!
        assertFalse(slot.inClass)
        assertEquals(layout.sectionTop(1).value, slot.y.value, 1e-3f)
    }

    @Test
    fun `time slot is null before the first class and after the last`() {
        val layout = GridLayout(sections())
        assertNull(layout.timeSlot(LocalTime.of(7, 0)))
        assertNull(layout.timeSlot(LocalTime.of(23, 0)))
    }
}
