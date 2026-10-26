package com.zhijian.schedule.core.notifications

import com.zhijian.schedule.core.data.db.ExamEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 考前两档提醒的时刻与请求码计算（纯逻辑，可单测） */
object ExamAlarms {

    /** 提前量（分钟）：考前一天 + 考前一小时 */
    val LEAD_MINUTES = listOf(24 * 60, 60)

    data class Slot(val at: LocalDateTime, val leadMinutes: Int)

    /** 一场考试未过期的提醒档位（按提前量从大到小） */
    fun slots(exam: ExamEntity, now: LocalDateTime): List<Slot> {
        val examAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(exam.examTime), ZoneId.systemDefault())
        return LEAD_MINUTES.mapNotNull { lead ->
            val at = examAt.minusMinutes(lead.toLong())
            if (at.isAfter(now)) Slot(at, lead) else null
        }
    }

    /**
     * requestCode 号段。课程闹钟直接用 courseId（小整数）当 requestCode，
     * 胶囊占用 3002-3004；考试从 1_000_000 起，examId × 2 + 槽位。
     */
    const val RC_BASE = 1_000_000

    fun requestCode(examId: Long, slotIndex: Int): Int =
        (RC_BASE + examId * 2 + slotIndex).toInt()

    /** 通知 id：与同步通知（2001/2002）、课程提醒（courseId）隔离 */
    fun notificationId(examId: Long): Int = (2_000_000 + examId).toInt()
}
