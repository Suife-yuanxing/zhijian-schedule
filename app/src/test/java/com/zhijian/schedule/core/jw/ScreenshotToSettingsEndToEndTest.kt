package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.repository.SectionTimePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 用户两张截图的端到端对照：
 * 图片 1（课表周视图）→ 抓取/识别得到的作息 → 图片 2（设置页作息列表）。
 *
 * 这条链路里没有任何一处硬编码时间：截图文字 → [SectionTimes.fromLines] →
 * [SectionTimePlan.plan] → 设置页按 sectionNo 逐行显示。
 */
class ScreenshotToSettingsEndToEndTest {
    /** 设置页截图上的 12 行（图片 2） */
    private val settingsRows: List<Pair<String, String>> =
        listOf(
            "08:20" to "09:05",
            "09:10" to "09:55",
            "10:10" to "10:55",
            "11:00" to "11:45",
            "14:20" to "15:05",
            "15:10" to "15:55",
            "16:05" to "16:50",
            "16:55" to "17:40",
            "18:20" to "19:05",
            "19:10" to "19:55",
            "20:00" to "20:45",
            "20:50" to "21:35",
        )

    /** 课表截图（图片 1）时间轴上真实写着的文字 */
    private val screenshotText: List<String> =
        listOf(
            "上午",
            "08:20 - 11:45",
            "08:20-09:55",
            "10:10-11:45",
            "下午",
            "14:20 - 17:40",
            "14:20 - 15:55",
            "16:05-17:40",
            "晚上",
            "18:20 - 21:35",
            "18:20-19:55",
            "20:00-21:35",
        )

    private fun localSections(): List<SectionTimeEntity> =
        SectionTimes.defaults().map { draft ->
            SectionTimeEntity(
                id = draft.sectionNo.toLong(),
                modeId = 1L,
                sectionNo = draft.sectionNo,
                periodNo = (draft.sectionNo - 1) / 2 + 1,
                session = if (draft.sectionNo <= 4) "AM" else "PM",
                startTime = draft.startTime,
                endTime = draft.endTime,
            )
        }

    @Test
    fun `screenshot timeline lands in settings rows unchanged`() {
        val extracted = SectionTimes.fromLines(screenshotText)
        val plan = SectionTimePlan.plan(localSections(), extracted, neededEndSection = 12)!!
        assertEquals(settingsRows.size, plan.size)
        plan.sortedBy { it.sectionNo }.forEachIndexed { i, section ->
            assertEquals("第 " + (i + 1) + " 行开始", settingsRows[i].first, section.startTime)
            assertEquals("第 " + (i + 1) + " 行结束", settingsRows[i].second, section.endTime)
        }
    }

    @Test
    fun `a school with different hours lands as that school writes them`() {
        // 换一所学校：上午两个大节 08:00-09:35 / 09:55-11:30，下午 13:30 开始
        val other =
            listOf(
                "上午 08:00 - 11:35",
                "08:00-09:35",
                "09:55-11:30",
                "下午 13:30 - 17:05",
            )
        val plan = SectionTimePlan.plan(localSections(), SectionTimes.fromLines(other), neededEndSection = 4)!!
        // 上午表头只是两个大节的并集，会被丢掉；落库的是大节拆出来的小节
        assertEquals("08:00", plan[0].startTime)
        assertEquals("09:35", plan[1].endTime)
        assertEquals("09:55", plan[2].startTime)
        assertEquals("11:30", plan[3].endTime)
        // 与图 2 那所学校的时间表完全不同 —— 证明作息没有被写死
        assertNotEquals("08:20", plan[0].startTime)
    }

    @Test
    fun `no timeline in the source never touches the settings rows`() {
        assertNull(SectionTimePlan.plan(localSections(), emptyList(), neededEndSection = 12))
    }
}
