package com.zhijian.schedule.core.notifications

import com.zhijian.schedule.core.notifications.ExamAlarms
import com.zhijian.schedule.core.data.db.ExamEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamAlarmsTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 12, 0)

    private fun examAt(at: LocalDateTime) = ExamEntity(
        id = 7, tableId = 1, name = "高等数学",
        examTime = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    @Test fun `未来考试给出两档提醒`() {
        val slots = ExamAlarms.slots(examAt(now.plusHours(30)), now)
        assertEquals(2, slots.size)
        assertEquals(24 * 60, slots[0].leadMinutes)
        assertEquals(60, slots[1].leadMinutes)
        assertEquals(now.plusHours(6), slots[0].at) // 30h - 24h
    }

    @Test fun `已过档位被剔除`() {
        val slots = ExamAlarms.slots(examAt(now.plusHours(10)), now)
        assertEquals(1, slots.size)
        assertEquals(60, slots.single().leadMinutes)
    }

    @Test fun `已开考的考试没有提醒`() {
        assertTrue(ExamAlarms.slots(examAt(now.minusMinutes(1)), now).isEmpty())
    }

    @Test fun `requestCode与课程号段隔离`() {
        val rc = ExamAlarms.requestCode(5, 1)
        assertTrue(rc >= ExamAlarms.RC_BASE)
        assertEquals(ExamAlarms.requestCode(5, 0) + 1, rc)
    }
}
