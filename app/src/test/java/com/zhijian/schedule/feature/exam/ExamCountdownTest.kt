package com.zhijian.schedule.feature.exam

import com.zhijian.schedule.feature.exam.ExamCountdown
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamCountdownTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 12, 0)

    private fun millis(at: LocalDateTime) =
        at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `倒计时徽标分档`() {
        assertEquals("3 天", ExamCountdown.badge(millis(now.plusDays(3).plusHours(1)), now))
        assertEquals("5 小时", ExamCountdown.badge(millis(now.plusHours(5)), now))
        assertEquals("30 分钟", ExamCountdown.badge(millis(now.plusMinutes(30)), now))
        assertEquals("已开考", ExamCountdown.badge(millis(now.minusMinutes(1)), now))
    }

    @Test fun `当天卡片标题带时刻_否则按天`() {
        assertEquals(
            "「高等数学」今天 14:00 开考",
            ExamCountdown.cardTitle("高等数学", millis(now.withHour(14)), now),
        )
        assertEquals(
            "距「高等数学」还有 3 天",
            ExamCountdown.cardTitle("高等数学", millis(now.plusDays(3)), now),
        )
    }
}
