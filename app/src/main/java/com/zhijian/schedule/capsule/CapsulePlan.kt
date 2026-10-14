package com.zhijian.schedule.capsule

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 实时胶囊的状态快照（纯数据，便于单测） */
sealed interface CapsulePlan {
    /** 状态栏 chip 上的短文字（shortCriticalText） */
    val chip: String

    /** 正在上课 */
    data class Ongoing(
        val course: CapsuleCourse,
        val progress: Int,
        val remainingMinutes: Long,
        /** 下课时刻（epoch 毫秒）：交系统 chronometer 每秒自绘，进程被冻结也照常走秒 */
        val endAtMillis: Long,
        override val chip: String = "上课中",
    ) : CapsulePlan

    /** 即将上课：已进入课前提醒窗口，画倒数进度条（联动「课前提醒」设置） */
    data class AboutToStart(
        val course: CapsuleCourse,
        val minutesUntil: Long,
        val progress: Int,
        /** 上课时刻（epoch 毫秒）：交系统 chronometer 每秒自绘 */
        val startAtMillis: Long,
        override val chip: String = "即将上课",
    ) : CapsulePlan

    /** 下节课（还没到课前提醒窗口）：不画进度条、不提升为流体云 */
    data class Upcoming(
        val course: CapsuleCourse,
        val minutesUntil: Long,
        override val chip: String = "下节课",
    ) : CapsulePlan

    /** 法定节假日（休）：当日课表课程按停课处理，胶囊按假期展示 */
    data class Holiday(
        val name: String,
        /** 假后最近一节课，如「明天 08:20 · 包装装潢设计」 */
        val nextText: String?,
        override val chip: String = "假期",
    ) : CapsulePlan

    /** 今天的课已上完（或今天没课） */
    data class DayDone(
        val hadClassesToday: Boolean,
        /** 如「明天 08:20 · 包装材料与检测」，未来 7 天都没有则为 null */
        val nextText: String?,
    ) : CapsulePlan {
        override val chip: String get() = if (hadClassesToday) "已结束" else "无课"
    }

    /** 还没有课表 */
    data object NoTable : CapsulePlan {
        override val chip: String = "纸间课表"
    }
}

data class CapsuleCourse(
    val name: String,
    val room: String,
    val color: Int,
    val start: LocalTime,
    val end: LocalTime,
)

fun buildCapsulePlan(
    timetable: TimetableEntity?,
    courses: List<CourseEntity>,
    sections: List<SectionTimeEntity>,
    now: LocalDateTime,
    /** 课前提醒提前量（分钟）；<=0（课前提醒关闭）时不产生「即将上课」状态 */
    reminderMinutes: Int,
    /** 今天是法定假日（休）时的节日名；调休补班日与平时传 null */
    holidayName: String? = null,
): CapsulePlan {
    val table = timetable ?: return CapsulePlan.NoTable
    val startDate = TimeUtils.parseDate(table.startDate) ?: return CapsulePlan.NoTable

    val today = now.toLocalDate()
    val nowTime = now.toLocalTime()

    // 法定假日：课表课程不执行，直接进入假期状态（不做上课/倒数判断）
    if (holidayName != null) {
        return CapsulePlan.Holiday(
            name = holidayName,
            nextText = nextOccurrenceText(startDate, table.totalWeeks, courses, sections, today),
        )
    }

    val week = TimeUtils.weekOfSemester(startDate, table.totalWeeks, today)
    val dow = TimeUtils.dayOfWeek(today)
    val todayCourses = if (week == null) emptyList() else {
        courses.asSequence()
            .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
            .sortedBy { it.startSection }
            .mapNotNull { entity -> courseOf(entity, sections) }
            .toList()
    }

    todayCourses
        .firstOrNull { !nowTime.isBefore(it.start) && nowTime.isBefore(it.end) }
        ?.let { c ->
            val total = secondsBetween(c.start, c.end)
            val elapsed = secondsBetween(c.start, nowTime).coerceAtMost(total)
            return CapsulePlan.Ongoing(
                course = c,
                progress = ((elapsed * 100) / total).toInt(),
                remainingMinutes = Duration.between(nowTime, c.end).toMinutes().coerceAtLeast(0),
                endAtMillis = epochMillisOf(now, c.end),
            )
        }

    todayCourses
        .firstOrNull { it.start.isAfter(nowTime) }
        ?.let { c ->
            val minutesUntil = Duration.between(nowTime, c.start).toMinutes().coerceAtLeast(0)
            // 课前提醒窗口：从 max(上一节课下课, 开课前 reminderMinutes) 起倒数
            val windowStart = if (reminderMinutes > 0) {
                val remindAt = c.start.minusMinutes(reminderMinutes.toLong())
                val prevEnd = todayCourses.lastOrNull { !it.end.isAfter(nowTime) }?.end
                if (prevEnd != null && prevEnd.isAfter(remindAt)) prevEnd else remindAt
            } else null
            if (windowStart != null && !nowTime.isBefore(windowStart)) {
                val span = secondsBetween(windowStart, c.start)
                val done = secondsBetween(windowStart, nowTime).coerceAtMost(span)
                return CapsulePlan.AboutToStart(
                    course = c,
                    minutesUntil = minutesUntil,
                    progress = ((done * 100) / span).toInt(),
                    startAtMillis = epochMillisOf(now, c.start),
                )
            }
            return CapsulePlan.Upcoming(course = c, minutesUntil = minutesUntil)
        }

    return CapsulePlan.DayDone(
        hadClassesToday = todayCourses.isNotEmpty(),
        nextText = nextOccurrenceText(startDate, table.totalWeeks, courses, sections, today),
    )
}

private fun courseOf(entity: CourseEntity, sections: List<SectionTimeEntity>): CapsuleCourse? {
    val start = sections.getOrNull(entity.startSection - 1)?.startTime?.let { TimeUtils.parseTime(it) }
    val end = sections.getOrNull(entity.endSection - 1)?.endTime?.let { TimeUtils.parseTime(it) }
    if (start == null || end == null || !start.isBefore(end)) return null
    return CapsuleCourse(name = entity.name, room = entity.room, color = entity.color, start = start, end = end)
}

private fun secondsBetween(from: LocalTime, to: LocalTime): Long =
    Duration.between(from, to).seconds.coerceAtLeast(1)

/** now 所在日期下 time 对应的 epoch 毫秒（课程时段不跨天） */
private fun epochMillisOf(now: LocalDateTime, time: LocalTime): Long =
    now.toLocalDate().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 未来 7 天里最近的一节课，如「明天 08:20 · 包装材料与检测」 */
private fun nextOccurrenceText(
    startDate: LocalDate,
    totalWeeks: Int,
    courses: List<CourseEntity>,
    sections: List<SectionTimeEntity>,
    today: LocalDate,
): String? {
    for (offset in 1..7) {
        val date = today.plusDays(offset.toLong())
        val week = TimeUtils.weekOfSemester(startDate, totalWeeks, date) ?: continue
        val dow = TimeUtils.dayOfWeek(date)
        val course = courses
            .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
            .sortedBy { it.startSection }
            .firstOrNull { courseOf(it, sections) != null } ?: continue
        val start = sections.getOrNull(course.startSection - 1)?.startTime ?: continue
        val label = if (offset == 1) "明天" else TimeUtils.dayName(dow)
        return "$label $start · ${course.name}"
    }
    return null
}
