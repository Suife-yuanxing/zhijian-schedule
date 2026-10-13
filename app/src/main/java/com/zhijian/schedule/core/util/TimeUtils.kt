package com.zhijian.schedule.core.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object TimeUtils {

    val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun parseDate(s: String?): LocalDate? = runCatching { LocalDate.parse(s, DATE_FMT) }.getOrNull()

    fun formatDate(d: LocalDate): String = d.format(DATE_FMT)

    fun parseTime(s: String?): LocalTime? = runCatching { LocalTime.parse(s?.trim()) }.getOrNull()

    fun formatTime(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm"))

    /** 周一到周日 → 1..7 */
    fun dayOfWeek(d: LocalDate): Int = d.dayOfWeek.value

    fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; 7 -> "周日"
        else -> ""
    }

    fun dayNameShort(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
        5 -> "五"; 6 -> "六"; 7 -> "日"
        else -> ""
    }

    /** 该日期是本学期第几周；<1 或超出 totalWeeks 返回 null */
    fun weekOfSemester(startDate: LocalDate, totalWeeks: Int, date: LocalDate): Int? {
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, date)
        val week = Math.floorDiv(days, 7).toInt() + 1
        return if (week in 1..totalWeeks) week else null
    }

    /** 学期第 week 周的某天日期 */
    fun dateOfWeekDay(startDate: LocalDate, week: Int, dayOfWeek: Int): LocalDate =
        startDate.plusDays(((week - 1) * 7 + (dayOfWeek - 1)).toLong())

    /** 计算当前节次（1 基），不在任何小节时间段内返回 null */
    fun sectionAt(now: LocalTime, sections: List<Pair<String, String>>): Int? {
        sections.forEachIndexed { i, (s, e) ->
            val st = parseTime(s) ?: return@forEachIndexed
            val et = parseTime(e) ?: return@forEachIndexed
            if (!now.isBefore(st) && !now.isAfter(et)) return i + 1
        }
        return null
    }

    /** 本地时间到某时刻的分钟差 */
    fun minutesUntil(from: LocalTime, to: LocalTime): Long =
        java.time.Duration.between(from, to).toMinutes()

    fun minutesText(minutes: Long): String = when {
        minutes <= 0 -> "已开始"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}
