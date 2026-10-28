package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.util.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取后的「课表信息核对」提示。
 *
 * 课表信息（学期 / 开学日期 / 总周数）不会跟着教务抓取改写——它决定周次如何映射到
 * 具体日期，写坏了整学期都会错，而用户很难发现。代价是换学校、换学期后旧值会安静地
 * 留在这儿，所以抓完主动提示一句，让用户自己去高级设置核对。
 */
class TimetableInfoAuditTest {
    @Test
    fun nothing_to_say_when_semester_filled_and_school_unchanged() {
        assertNull(
            TimetableInfoAudit.audit(
                semester = "2026-2027-1",
                schoolChanged = false,
            ),
        )
    }

    @Test
    fun blank_semester_asks_user_to_fill_it_in() {
        assertEquals(
            "课表信息未填写学期，建议在高级设置里核对",
            TimetableInfoAudit.audit(semester = "", schoolChanged = false),
        )
    }

    @Test
    fun blank_semester_and_changed_school_are_reported_together() {
        val hint = TimetableInfoAudit.audit(semester = " ", schoolChanged = true)
        assertTrue(hint!!.contains("学期"))
        assertTrue(hint.contains("教务地址"))
    }

    @Test
    fun changed_school_alone_asks_user_to_recheck_semester_and_start_date() {
        assertEquals(
            "教务地址与上次同步时不同，建议核对学期与开学日期",
            TimetableInfoAudit.audit(semester = "2026-2027-1", schoolChanged = true),
        )
    }

    @Test
    fun week_mask_length_can_never_prove_a_total_weeks_overflow() {
        // 记一笔陷阱：WeekMask 会把超出 totalWeeks 的周次直接丢掉，
        // 掩码长度恒等于 totalWeeks，用它判越界永远为假。
        val clamped = WeekMask.fromWeeks(20, (1..30).toList())
        assertEquals(20, clamped.length)
    }

    @Test
    fun same_host_with_new_query_is_not_a_school_change() {
        // 学期参数会变，但主机没变——这不是换学校
        assertEquals(
            TimetableInfoAudit.hostOf("https://jw.example.edu.cn/jsxsd/xskb_list.do?semester=2026-1"),
            TimetableInfoAudit.hostOf("https://jw.example.edu.cn/jsxsd/xskb_list.do?semester=2025-2"),
        )
    }
}
