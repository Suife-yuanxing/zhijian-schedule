package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
     *
     * ## 断言为什么变了
     * 文案被拆成了两半：这里只说**后果**（「周次可能不准」），
     * 「怎么做」交给 [SemesterStartDateMemory.prompt]。原来那句
     * 「建议在高级设置里核对」与那边的提示说的是同一件事，并排出现读起来
     * 像两个问题。断言的**意图没有放宽**——它仍然要求这条提示说清「会出什么事」，
     * 只是不再要求它同时给出操作路径（那件事另有断言在别处钉着）。
     */
    @Test
    fun missing_start_date_names_the_consequence() {
        val hint = TimetableMetaHint.missingHint(JwMeta(semester = "2026-2027学年第一学期"))
        assertNotNull(hint)
        assertTrue("必须说清后果，否则用户没有理由去填", hint!!.contains("周次"))
        assertTrue("「可能不准」而不是「一定不准」：来源没声明不等于已知是错的", hint.contains("可能"))
        assertFalse("操作路径由 SemesterStartDateMemory 给，这里不该重一句", hint.contains("设置"))
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
