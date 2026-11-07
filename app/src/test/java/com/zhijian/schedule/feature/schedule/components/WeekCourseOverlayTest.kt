package com.zhijian.schedule.feature.schedule.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class WeekCourseOverlayTest {
    private val now = LocalDateTime.of(2026, 11, 7, 10, 30)
    private val start = LocalTime.of(10, 0)
    private val end = LocalTime.of(11, 0)

    @Test
    fun returnsNullWhenSectionTimesMissing() {
        assertNull(remainingMinutes(now, null, end))
        assertNull(remainingMinutes(now, start, null))
    }

    @Test
    fun returnsNullOutsideClassTime() {
        // 还没开始（开始那一刻起才算「正在上」）
        assertNull(remainingMinutes(LocalDateTime.of(2026, 11, 7, 9, 59), start, end))
        // 刚结束（结束那一刻起不再显示倒计时）
        assertNull(remainingMinutes(now, start, LocalTime.of(10, 30)))
    }

    @Test
    fun returnsRemainingMinutesInsideClass() {
        assertEquals(30, remainingMinutes(now, start, end))
        // 开始那一瞬也算在课上
        assertEquals(60, remainingMinutes(LocalDateTime.of(2026, 11, 7, 10, 0), start, end))
    }

    @Test
    fun clampsSubMinuteRemainderToOne() {
        val late = LocalDateTime.of(2026, 11, 7, 10, 59, 30)
        assertEquals(1, remainingMinutes(late, start, end))
    }
}
