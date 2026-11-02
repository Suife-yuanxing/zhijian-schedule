package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「课表信息跟着抓取走」的落库决策：教务声明了才覆盖，没声明的字段原样保留。
 */
class TimetableMetaPlanTest {
    private val entity =
        com.zhijian.schedule.core.data.db.TimetableEntity(
            id = 1L,
            name = "我的课表",
            semester = "旧学期",
            startDate = "2026-03-02",
            totalWeeks = 20,
        )

    @Test
    fun `declared fields overwrite and blank ones keep local values`() {
        val planned =
            TimetableMetaPlan.apply(
                entity,
                JwMeta(semester = "2026-2027学年第一学期", startDate = "2026-08-31", totalWeeks = 18),
            )
        assertEquals("2026-2027学年第一学期", planned.semester)
        assertEquals("2026-08-31", planned.startDate)
        assertEquals(18, planned.totalWeeks)
    }

    @Test
    fun `missing fields keep local values independently`() {
        val planned = TimetableMetaPlan.apply(entity, JwMeta(semester = "2026-2027学年第一学期"))
        assertEquals("2026-2027学年第一学期", planned.semester)
        assertEquals("2026-03-02", planned.startDate)
        assertEquals(20, planned.totalWeeks)
    }

    /** 周历失败时不该把学期名一起丢：空 meta 不产生变更 */
    @Test
    fun `empty meta is a no op`() {
        assertEquals(entity, TimetableMetaPlan.apply(entity, JwMeta()))
    }

    /** 异常总周数与手填口径同样收口到 1..30；非正数视为「未声明」保留本地值 */
    @Test
    fun `total weeks is capped like the manual input`() {
        assertEquals(30, TimetableMetaPlan.apply(entity, JwMeta(totalWeeks = 99)).totalWeeks)
        assertEquals(20, TimetableMetaPlan.apply(entity, JwMeta(totalWeeks = -5)).totalWeeks)
    }

    /**
     * 掩码长度：来源声明了就按声明建，没声明才用本地值。
     *
     * 这条守的是「本地 16 周、教务 18 周」的经典翻车：按本地建掩码，第 17/18 周
     * 在建掩码那一刻就被截掉，截在 Diff 之前，预览里连「有变化」都看不见。
     */
    @Test
    fun `mask length follows the declared total weeks`() {
        assertEquals(18, TimetableMetaPlan.maskLength(localWeeks = 16, meta = JwMeta(totalWeeks = 18)))
        assertEquals(16, TimetableMetaPlan.maskLength(localWeeks = 16, meta = JwMeta(totalWeeks = 0)))
        assertEquals(16, TimetableMetaPlan.maskLength(localWeeks = 16, meta = null))
    }

    /** 异常声明与 [TimetableMetaPlan.apply] 同口径收口，不会把掩码撑到 99 位 */
    @Test
    fun `mask length caps and ignores non positive declarations`() {
        assertEquals(30, TimetableMetaPlan.maskLength(localWeeks = 20, meta = JwMeta(totalWeeks = 99)))
        assertEquals(20, TimetableMetaPlan.maskLength(localWeeks = 20, meta = JwMeta(totalWeeks = -5)))
    }
}
