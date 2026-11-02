package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 「页面写着当前第 N 周」→ 第 1 周周一。
 *
 * 这是给「课表接口不含日期」的那 10 家厂商留的退路：今天是已知的，N 是已知的，
 * 第 1 周周一就是唯一解。四道闸门必须各自成立，任何一条不满足都返回 null。
 */
class JwCurrentWeekInferenceTest {
    /** 2026-11-02 是周一，正是用户报障那天 */
    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun week_one_monday_is_today() {
        assertEquals("2026-11-02", JwCurrentWeekInference.infer(1, 20, monday))
    }

    /** 用户报障场景：11 月 2 日处在第 10 周，第 1 周周一应是 2026-08-31 */
    @Test
    fun week_ten_on_report_date_maps_to_august_31() {
        assertEquals("2026-08-31", JwCurrentWeekInference.infer(10, 20, monday))
    }

    /** 同周内的周三 / 周日必须推出同一个周一，不能随当天星期漂移 */
    @Test
    fun same_week_any_weekday_yields_same_start() {
        val wednesday = LocalDate.of(2026, 11, 4)
        val sunday = LocalDate.of(2026, 11, 8)
        assertEquals("2026-08-31", JwCurrentWeekInference.infer(10, 20, wednesday))
        assertEquals("2026-08-31", JwCurrentWeekInference.infer(10, 20, sunday))
    }

    /** 跨年也要对：第 1 周落在去年 */
    @Test
    fun inference_crosses_year_boundary() {
        // 2027-01-11 是周一，第 1 周周一 = 2026-08-31（相差 19 周）
        assertEquals("2026-08-31", JwCurrentWeekInference.infer(20, 20, LocalDate.of(2027, 1, 11)))
    }

    @Test
    fun out_of_range_or_unknown_total_weeks_is_rejected() {
        assertNull(JwCurrentWeekInference.infer(21, 20, monday))
        assertNull(JwCurrentWeekInference.infer(0, 20, monday))
        assertNull(JwCurrentWeekInference.infer(-3, 20, monday))
        assertNull(JwCurrentWeekInference.infer(99, 20, monday))
    }

    /** 总周数未知（0）时只校验周次本身，仍能推出结果 */
    @Test
    fun unknown_total_weeks_still_infers() {
        assertEquals("2026-08-31", JwCurrentWeekInference.infer(10, 0, monday))
    }

    /** 推出来的起点若把今天排除在学期外，说明 N 与「今天」不同源（跨学期 / 页面缓存） */
    @Test
    fun start_that_excludes_today_is_rejected() {
        // 第 5 周 + 总周数 2 → 区间只有 2 周，装不下今天
        assertNull(JwCurrentWeekInference.infer(5, 2, monday))
    }
}
