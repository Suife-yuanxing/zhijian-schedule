package com.zhijian.schedule

import com.zhijian.schedule.core.jw.GradeRecord
import com.zhijian.schedule.core.jw.GradeTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeTrendTest {
    private fun rec(term: String, name: String, credit: Double, gpa: Double?, retake: String = "") =
        GradeRecord(term, "C$name", name, "85", "", credit, null, gpa, retake, "", "", "", "")

    @Test fun `按学分加权且只计有绩点课程`() {
        val s = GradeTrend.perTerm(
            listOf(rec("2025-2026-1", "A", 3.0, 4.0), rec("2025-2026-1", "B", 2.0, 3.0)),
        ).single()
        assertEquals(3.6, s.gpa!!, 1e-9) // (3*4 + 2*3) / 5
        assertEquals(5.0, s.credits, 1e-9)
        assertEquals(2, s.courseCount)
    }

    @Test fun `补考重修与无绩点行排除`() {
        val s = GradeTrend.latest(
            listOf(
                rec("2025-2026-1", "A", 3.0, 4.0),
                rec("2025-2026-1", "B", 2.0, 1.0, retake = "2025-2026-2"),
                rec("2025-2026-1", "C", 2.0, null),
            ),
        )!!
        assertEquals(1, s.courseCount)
        assertEquals(4.0, s.gpa!!, 1e-9)
    }

    @Test fun `latest取学期名最大的一期`() {
        val latest = GradeTrend.latest(
            listOf(rec("2024-2025-2", "A", 3.0, 3.0), rec("2025-2026-1", "B", 3.0, 4.0)),
        )!!
        assertEquals("2025-2026-1", latest.term)
    }

    @Test fun `空数据返回null`() {
        assertNull(GradeTrend.latest(emptyList()))
    }
}
