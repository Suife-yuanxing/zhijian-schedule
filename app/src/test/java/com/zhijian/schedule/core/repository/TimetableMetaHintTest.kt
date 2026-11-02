package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入预览里那句「课表信息将随抓取同步…」的两个判断：
 * 会变什么（只列变化）、还缺什么（点名开学日期）。
 */
class TimetableMetaHintTest {
    private val local =
        TimetableMetaHint.LocalMeta(
            semester = "春季学期",
            startDate = "2026-11-02",
            totalWeeks = 20,
        )

    @Test
    fun unchanged_fields_are_not_listed() {
        assertEquals(emptyList<String>(), TimetableMetaHint.changedLabels(local, local))
    }

    @Test
    fun only_changed_fields_appear() {
        val after = local.copy(semester = "2026-2027学年第一学期")
        assertEquals(listOf("学期「2026-2027学年第一学期」"), TimetableMetaHint.changedLabels(local, after))
    }

    @Test
    fun start_date_and_weeks_are_reported_separately() {
        val after = local.copy(startDate = "2026-08-31", totalWeeks = 18)
        assertEquals(
            listOf("第 1 周 2026-08-31", "总周数 18"),
            TimetableMetaHint.changedLabels(local, after),
        )
    }

    /**
     * 这是本次修复的核心提醒：正方 / 青果能同步学期名但拿不到开学日期，
     * 旧值（建表当天）会安静留下，整学期周次平移而界面上看不出异常。
     */
    @Test
    fun missing_start_date_is_named_explicitly() {
        val hint = TimetableMetaHint.missingHint(JwMeta(semester = "2026-2027学年第一学期"))
        assertTrue(hint!!.contains("开学日期"))
        assertTrue(hint.contains("核对"))
    }

    @Test
    fun no_warning_when_start_date_is_declared() {
        assertNull(
            TimetableMetaHint.missingHint(
                JwMeta(semester = "2026-2027学年第一学期", startDate = "2026-08-31", totalWeeks = 20),
            ),
        )
    }

    @Test
    fun no_meta_means_no_warning() {
        assertNull(TimetableMetaHint.missingHint(null))
    }
}
