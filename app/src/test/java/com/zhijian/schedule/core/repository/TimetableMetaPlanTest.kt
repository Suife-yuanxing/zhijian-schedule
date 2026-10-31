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
}
