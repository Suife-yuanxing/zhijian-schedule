package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * .ics 日历文件 → 课程草稿（方案 P1：日历导入）。
 * 支持行折叠、TZID/UTC 时间、RRULE WEEKLY(BYDAY/UNTIL/COUNT/INTERVAL) 展开为周次掩码；
 * 时间按当前课表作息映射为节次；同一课程多次出现时周次取并集。
 */
object IcsParser {

    private val TARGET_PROPS = setOf("SUMMARY", "DTSTART", "DTEND", "RRULE", "LOCATION", "DESCRIPTION")

    fun parse(
        text: String,
        totalWeeks: Int,
        semesterStart: LocalDate?,
        sections: List<SectionTimeEntity>,
    ): TableMapping.Result {
        val events = parseEvents(text)
        if (events.isEmpty()) {
            return TableMapping.Result(emptyList(), 0, listOf("没有找到日历事件（VEVENT）"))
        }
        if (semesterStart == null) {
            return TableMapping.Result(emptyList(), 0, listOf("课表开学日期无效，无法换算周次"))
        }
        val sortedSections = sections.sortedBy { it.sectionNo }
        val accList = mutableListOf<Acc>()
        var skipped = 0

        events.forEach { props ->
            val summary = props["SUMMARY"]?.let(::unescape)?.trim().orEmpty()
            val dtStart = parseDateTime(props["DTSTART"])
            if (summary.isEmpty() || dtStart == null) {
                skipped++
                return@forEach
            }
            val dtEnd = parseDateTime(props["DTEND"])
            val startSection = sectionAt(sortedSections, dtStart.toLocalTime()) ?: 1
            val endSection = maxOf(
                startSection,
                sectionAt(sortedSections, dtEnd?.toLocalTime() ?: dtStart.toLocalTime().plusMinutes(45)) ?: startSection,
            )
            val description = props["DESCRIPTION"]?.let(::unescape).orEmpty()
            val location = props["LOCATION"]?.let(::unescape)?.trim().orEmpty()
            val room = location.ifBlank { extract(description, "教室", "地点", "上课地点") }
            val teacher = extract(description, "教师", "老师", "授课教师")
            val startDate = dtStart.toLocalDate()

            expandDates(startDate, props["RRULE"]).forEach { date ->
                val week = TimeUtils.weekOfSemester(semesterStart, totalWeeks, date) ?: return@forEach
                val day = date.dayOfWeek.value
                val idx = accList.indexOfFirst { o ->
                    o.name == summary && o.day == day && o.start == startSection && o.end == endSection &&
                        compatible(o.room, room) && compatible(o.teacher, teacher)
                }
                val acc = if (idx < 0) {
                    Acc(summary, day, startSection, endSection, room, teacher).also { accList += it }
                } else {
                    accList[idx]
                }
                if (acc.room.isBlank()) acc.room = room
                if (acc.teacher.isBlank()) acc.teacher = teacher
                acc.weeks += week
            }
        }

        if (accList.isEmpty()) {
            return TableMapping.Result(
                emptyList(), skipped,
                listOf("事件都没能匹配到学期周次，请检查开学日期是否与课表一致"),
            )
        }
        val drafts = accList
            .map { acc ->
                CourseDraft(
                    name = acc.name,
                    teacher = acc.teacher,
                    room = acc.room,
                    dayOfWeek = acc.day,
                    startSection = acc.start,
                    endSection = acc.end,
                    weekMask = WeekMask.fromWeeks(totalWeeks, acc.weeks),
                )
            }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
        val warnings = mutableListOf<String>()
        if (skipped > 0) warnings += "已跳过 $skipped 个无效事件（缺标题/全天事件等）"
        return TableMapping.Result(drafts, skipped, warnings)
    }

    // ---------- 解析 ----------

    private fun parseEvents(text: String): List<Map<String, String>> {
        val lines = unfold(text)
        val events = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null
        var depth = 0
        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    current = mutableMapOf()
                    depth = 0
                }
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    current?.let { events += it }
                    current = null
                }
                current != null -> when {
                    line.startsWith("BEGIN:", ignoreCase = true) -> depth++
                    line.startsWith("END:", ignoreCase = true) -> depth--
                    depth == 0 -> {
                        val name = line.substringBefore(':').substringBefore(';').uppercase().trim()
                        if (name in TARGET_PROPS && !current!!.containsKey(name)) {
                            propertyValue(line)?.let { current!![name] = it }
                        }
                    }
                }
            }
        }
        return events
    }

    /** RFC 5545 行折叠：以空格/Tab 开头的行接续上一行 */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split("\r\n", "\n", "\r").forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + line.substring(1)
            } else {
                out += line
            }
        }
        return out
    }

    /** 第一个不在引号内的冒号之后为属性值（TZID="..." 可能含冒号） */
    private fun propertyValue(line: String): String? {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            when (c) {
                '"' -> inQuotes = !inQuotes
                ':' -> if (!inQuotes) return line.substring(i + 1)
            }
        }
        return null
    }

    private fun parseDateTime(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        var v = raw.trim()
        val utc = v.endsWith("Z", ignoreCase = true)
        if (utc) v = v.dropLast(1)
        val digits = v.replace("T", "").trim()
        if (digits.length != 12 && digits.length != 14) return null
        if (!digits.all { it.isDigit() }) return null
        return runCatching {
            val dt = LocalDateTime.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(),
                digits.substring(10, 12).toInt(),
            )
            if (utc) dt.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() else dt
        }.getOrNull()
    }

    private fun expandDates(start: LocalDate, rrule: String?): List<LocalDate> {
        if (rrule.isNullOrBlank()) return listOf(start)
        val parts = rrule.split(';').mapNotNull { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) null else seg.substring(0, i).uppercase().trim() to seg.substring(i + 1).trim()
        }.toMap()
        if (parts["FREQ"]?.uppercase() != "WEEKLY") return listOf(start)
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()?.coerceAtLeast(0)
        val until = parts["UNTIL"]?.let { parseDateTime(it)?.toLocalDate() }
        val byDays = parts["BYDAY"]?.split(',')
            ?.mapNotNull { dayToken(it) }?.distinct()?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(start.dayOfWeek)
        if (count == 0) return emptyList()

        val firstMonday = start.with(DayOfWeek.MONDAY)
        val dates = mutableListOf<LocalDate>()
        var week = 0
        while (dates.size < 400 && week <= 200) {
            var produced = false
            for (d in byDays) {
                if (count != null && dates.size >= count) break
                val date = firstMonday.plusWeeks((week * interval).toLong()).plusDays((d.value - 1).toLong())
                if (date.isBefore(start)) continue
                if (until != null && date.isAfter(until)) continue
                dates += date
                produced = true
            }
            if (!produced && until != null &&
                firstMonday.plusWeeks(((week + 1) * interval).toLong()).isAfter(until)
            ) break
            week++
        }
        return dates.sorted()
    }

    private fun dayToken(token: String): DayOfWeek? = when (token.trim().takeLast(2).uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun sectionAt(sections: List<SectionTimeEntity>, time: LocalTime): Int? {
        if (sections.isEmpty()) return null
        sections.forEach { s ->
            val st = TimeUtils.parseTime(s.startTime) ?: return@forEach
            val et = TimeUtils.parseTime(s.endTime) ?: return@forEach
            if (!time.isBefore(st) && !time.isAfter(et)) return s.sectionNo
        }
        return sections.minByOrNull { s ->
            val st = TimeUtils.parseTime(s.startTime) ?: return@minByOrNull Long.MAX_VALUE
            abs(java.time.Duration.between(st, time).toMinutes())
        }?.sectionNo
    }

    private fun extract(description: String, vararg keys: String): String {
        if (description.isBlank()) return ""
        keys.forEach { key ->
            Regex("""$key[：:]\s*([^\s·,，;；|]{1,20})""").find(description)?.let {
                return it.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun unescape(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun compatible(a: String, b: String): Boolean = a.isBlank() || b.isBlank() || a == b

    private class Acc(
        val name: String,
        val day: Int,
        val start: Int,
        val end: Int,
        var room: String,
        var teacher: String,
    ) {
        val weeks = sortedSetOf<Int>()
    }
}
