package com.zhijian.schedule.feature.importer.ocr

import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.feature.importer.ocr.OcrLine
import com.zhijian.schedule.feature.importer.ocr.OcrTimetableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTimetableParserTest {
    private fun line(
        text: String,
        cx: Float,
        cy: Float,
        w: Float = 60f,
        h: Float = 30f,
    ) = OcrLine(text, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    private fun jwHeaders(): List<OcrLine> =
        listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
            .mapIndexed { i, name -> line(name, 400f + i * 300f, 100f, 100f, 40f) }

    @Test
    fun parsesJwStyleGridWithBigSectionAnchors() {
        val lines = mutableListOf<OcrLine>()
        lines += jwHeaders()
        listOf("一", "二", "三", "四", "五", "六").forEachIndexed { i, n ->
            lines += line("第${n}大节", 120f, 160f + i * 300f, 100f, 40f)
        }
        lines += line("包装材料与检测", 400f, 155f, 180f)
        lines += line("张三", 400f, 190f)
        lines += line("1-4(周)", 400f, 225f, 90f)
        lines += line("8A206", 400f, 260f, 80f)
        lines += line("包装装潢设计", 1000f, 755f, 180f)
        lines += line("李四", 1000f, 790f)
        lines += line("5-8(周)", 1000f, 825f, 90f)
        lines += line("10-305", 1000f, 860f, 80f)

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertEquals(2, result.drafts.size)
        val c1 = result.drafts.first { it.name == "包装材料与检测" }
        assertEquals(1, c1.dayOfWeek)
        assertEquals(1, c1.startSection)
        assertEquals(2, c1.endSection)
        assertEquals("张三", c1.teacher)
        assertEquals("8A206", c1.room)
        assertEquals(listOf(1, 2, 3, 4), WeekMask.weeks(c1.weekMask))

        val c2 = result.drafts.first { it.name == "包装装潢设计" }
        assertEquals(3, c2.dayOfWeek)
        assertEquals(5, c2.startSection)
        assertEquals(6, c2.endSection)
        assertEquals("李四", c2.teacher)
        assertEquals("10-305", c2.room)
        assertEquals(listOf(5, 6, 7, 8), WeekMask.weeks(c2.weekMask))
    }

    @Test
    fun fallsBackToProportionalRowsWithoutAnchors() {
        val lines = mutableListOf<OcrLine>()
        listOf("周一", "周二", "周三").forEachIndexed { i, name ->
            lines += line(name, 400f + i * 300f, 100f, 100f, 40f)
        }
        lines += line("语文", 400f, 210f)
        lines += line("A101", 400f, 240f, 80f)
        lines += line("数学", 1000f, 510f)
        lines += line("B202", 1000f, 540f, 80f)

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertEquals(2, result.drafts.size)
        val chinese = result.drafts.first { it.name == "语文" }
        val math = result.drafts.first { it.name == "数学" }
        assertEquals(1, chinese.dayOfWeek)
        assertEquals(3, math.dayOfWeek)
        assertEquals(3, chinese.startSection)
        assertEquals(4, chinese.endSection)
        assertEquals(11, math.startSection)
        assertEquals(12, math.endSection)
        assertEquals("A101", chinese.room)
        assertEquals("B202", math.room)
    }

    @Test
    fun parsesTextLinesWithoutHeaders() {
        val lines =
            listOf(
                line("包装材料与检测", 300f, 100f, 200f),
                line("周一 3-4节", 300f, 140f, 150f),
                line("1-16周", 300f, 180f, 90f),
                line("8A206", 300f, 220f, 80f),
                line("张三", 300f, 260f),
            )

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("包装材料与检测", d.name)
        assertEquals(1, d.dayOfWeek)
        assertEquals(3, d.startSection)
        assertEquals(4, d.endSection)
        assertEquals("8A206", d.room)
        assertEquals("张三", d.teacher)
        assertEquals(16, WeekMask.count(d.weekMask))
        assertTrue(WeekMask.contains(d.weekMask, 1))
        assertTrue(WeekMask.contains(d.weekMask, 16))
        assertTrue(!WeekMask.contains(d.weekMask, 17))
    }

    @Test
    fun bracketSectionsOverrideRowEstimate() {
        val lines = mutableListOf<OcrLine>()
        listOf("周一", "周二", "周三").forEachIndexed { i, name ->
            lines += line(name, 400f + i * 300f, 100f, 100f, 40f)
        }
        lines += line("高等数学", 400f, 300f, 160f)
        lines += line("[09-10节]", 400f, 340f, 140f)

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("高等数学", d.name)
        assertEquals(9, d.startSection)
        assertEquals(10, d.endSection)
    }

    @Test
    fun mergesSameCourseAcrossPages() {
        val page1 =
            listOf(
                line("包装材料与检测", 300f, 100f, 200f),
                line("周一 3-4节", 300f, 140f, 150f),
                line("1-16周", 300f, 180f, 90f),
                line("8A206", 300f, 220f, 80f),
                line("张三", 300f, 260f),
            )
        val page2 =
            listOf(
                line("包装材料与检测", 300f, 100f, 200f),
                line("周一 3-4节", 300f, 140f, 150f),
                line("17-20周", 300f, 180f, 90f),
            )

        val result = OcrTimetableParser.parse(listOf(page1, page2), totalWeeks = 20, totalSections = 12)

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals(20, WeekMask.count(d.weekMask))
        assertEquals("8A206", d.room)
        assertEquals("张三", d.teacher)
    }

    @Test
    fun parsesParityWeeksSuffix() {
        val lines =
            listOf(
                line("体育", 300f, 100f),
                line("周三 5-6节", 300f, 140f, 150f),
                line("1-12双周", 300f, 180f, 110f),
                line("田径场", 300f, 220f, 90f),
            )

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertEquals(1, result.drafts.size)
        val d = result.drafts[0]
        assertEquals("体育", d.name)
        assertEquals(3, d.dayOfWeek)
        assertEquals("田径场", d.room)
        assertEquals(listOf(2, 4, 6, 8, 10, 12), WeekMask.weeks(d.weekMask))
    }

    @Test
    fun interpolatesMissingDayHeaderByColumnPosition() {
        val lines = mutableListOf<OcrLine>()
        listOf(400f, 1000f, 1300f, 1600f).forEachIndexed { i, cx ->
            lines += line(listOf("星期一", "星期三", "星期四", "星期五")[i], cx, 100f, 100f, 40f)
        }
        lines += line("大学英语", 700f, 175f, 120f)
        lines += line("王五", 700f, 210f)
        lines += line("1-16", 700f, 245f, 80f)
        lines += line("A305", 700f, 280f, 80f)

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        val d = result.drafts.single()
        assertEquals(2, d.dayOfWeek)
        assertEquals("大学英语", d.name)
        assertEquals("王五", d.teacher)
        assertEquals("A305", d.room)
        assertEquals(16, WeekMask.count(d.weekMask))
    }

    @Test
    fun treatsBareRangeLineAsWeeksWhenZhouCharMissing() {
        val lines =
            listOf(
                line("包装材料与检测", 300f, 100f, 200f),
                line("周一 3-4节", 300f, 140f, 150f),
                line("1-16ā", 300f, 180f, 80f),
                line("8A206", 300f, 220f, 80f),
                line("张三", 300f, 260f),
            )

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        val d = result.drafts.single()
        assertEquals(16, WeekMask.count(d.weekMask))
        assertTrue(WeekMask.contains(d.weekMask, 16))
        assertEquals("张三", d.teacher)
    }

    @Test
    fun doesNotTreatRoomLikeRangeAsWeeks() {
        val lines =
            listOf(
                line("包装材料与检测", 300f, 100f, 200f),
                line("周一 3-4节", 300f, 140f, 150f),
                line("10-305", 300f, 180f, 80f),
                line("张三", 300f, 220f),
            )

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        val d = result.drafts.single()
        assertEquals("10-305", d.room)
        assertTrue(WeekMask.count(d.weekMask) > 16)
    }

    @Test
    fun reportsWarningWhenNothingParsed() {
        val lines = listOf(line("欢迎使用某某大学教务系统", 300f, 100f, 400f))

        val result = OcrTimetableParser.parse(listOf(lines), totalWeeks = 20, totalSections = 12)

        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
