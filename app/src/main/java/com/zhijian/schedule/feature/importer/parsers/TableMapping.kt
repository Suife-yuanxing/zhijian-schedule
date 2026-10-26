package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask

/**
 * 表格（CSV / Excel）→ 课程列表 的统一映射。
 * 约定表头：课程名/星期/开始节/结束节/老师/地点/周数；无表头时按位置解析。
 */
object TableMapping {

    private val HEADERS_NAME = listOf("课程名称", "课程名", "课程", "名称", "course", "name")
    private val HEADERS_DAY = listOf("星期", "周几", "上课日", "day", "weekday")
    private val HEADERS_START = listOf("开始节", "起始节", "开始", "起节", "start")
    private val HEADERS_END = listOf("结束节", "结束", "止节", "end")
    private val HEADERS_TEACHER = listOf("老师", "教师", "授课教师", "teacher")
    private val HEADERS_ROOM = listOf("教室", "地点", "上课地点", "room", "place")
    private val HEADERS_WEEKS = listOf("周数", "周次", "上课周", "weeks", "week")
    private val HEADERS_SECTION = listOf("节次", "节", "section")
    private val HEADERS_TIME = listOf("时间", "上课时间", "time")

    internal fun map(rows: List<List<String>>, totalWeeks: Int): ParseResult {
        val nonEmpty = rows.filter { row -> row.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return ParseResult.error("表格为空")

        var headerRow: List<String>? = null
        var dataStart = 0
        for (i in 0 until minOf(2, nonEmpty.size)) {
            if (looksLikeHeader(nonEmpty[i])) {
                headerRow = nonEmpty[i]
                dataStart = i + 1
                break
            }
        }

        val idx = headerRow?.let { resolveColumns(it) }
        val drafts = mutableListOf<CourseDraft>()
        var skipped = 0

        for (i in dataStart until nonEmpty.size) {
            val row = nonEmpty[i]
            val draft = if (idx != null) mapWithHeader(row, idx, totalWeeks)
            else mapPositional(row, totalWeeks)
            if (draft != null) drafts += draft else skipped++
        }
        return ParseResult(drafts, skipped)
    }

    private data class Columns(
        val name: Int, val day: Int, val start: Int, val end: Int,
        val teacher: Int, val room: Int, val weeks: Int,
    )

    private fun looksLikeHeader(row: List<String>): Boolean {
        val joined = row.joinToString("|").lowercase()
        val hits = listOf(HEADERS_NAME, HEADERS_DAY, HEADERS_START, HEADERS_WEEKS)
            .count { group -> group.any { joined.contains(it) } }
        return hits >= 2
    }

    private fun resolveColumns(row: List<String>): Columns {
        fun find(keys: List<String>): Int = row.indexOfFirst { cell ->
            val c = cell.trim().lowercase()
            keys.any { c.contains(it) }
        }
        return Columns(
            name = find(HEADERS_NAME),
            day = find(HEADERS_DAY),
            start = find(HEADERS_START).takeIf { it >= 0 } ?: find(HEADERS_SECTION),
            end = find(HEADERS_END),
            teacher = find(HEADERS_TEACHER),
            room = find(HEADERS_ROOM),
            weeks = find(HEADERS_WEEKS),
        )
    }

    private fun mapWithHeader(row: List<String>, c: Columns, totalWeeks: Int): CourseDraft? {
        fun cell(i: Int): String = if (i in row.indices) row[i].trim() else ""
        val name = cell(c.name)
        if (name.isBlank()) return null
        if (HEADERS_TIME.any { name == it }) return null
        val day = parseDay(cell(c.day)) ?: return null
        val startRaw = cell(c.start)
        var start = parseSection(startRaw)
        var end = if (c.end >= 0) parseSection(cell(c.end)) else null
        if (start != null && end == null) {
            end = if (startRaw.contains("-")) {
                parseSection(startRaw.substringAfter("-")).also { start = parseSection(startRaw.substringBefore("-")) ?: start }
            } else start
        }
        if (start == null || end == null) return null
        val weeksText = cell(c.weeks)
        val mask = WeekMask.parseExpression(totalWeeks, weeksText)
            ?: WeekMask.of(totalWeeks)
        return CourseDraft(
            name = name,
            teacher = cell(c.teacher),
            room = cell(c.room),
            dayOfWeek = day,
            startSection = start,
            endSection = maxOf(start, end),
            weekMask = mask,
        )
    }

    private fun mapPositional(row: List<String>, totalWeeks: Int): CourseDraft? {
        val cells = row.map { it.trim() }
        if (cells.size < 3) return null
        val name = cells[0]
        if (name.isBlank()) return null
        val day = parseDay(cells[1]) ?: return null
        val start = parseSection(cells[2]) ?: return null
        val end = if (cells.size > 3) parseSection(cells[3]) ?: start else start
        val teacher = cells.getOrElse(4) { "" }.takeIf { it.toIntOrNull() == null } ?: ""
        val room = cells.getOrElse(5) { "" }
        val weeksText = cells.getOrElse(6) { "" }
        return CourseDraft(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startSection = start,
            endSection = maxOf(start, end),
            weekMask = WeekMask.parseExpression(totalWeeks, weeksText) ?: WeekMask.of(totalWeeks),
        )
    }

    internal fun parseDay(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { if (it in 1..7) return it }
        val zh = listOf("一", "二", "三", "四", "五", "六", "日")
        zh.forEachIndexed { i, c -> if (t.contains(c)) return i + 1 }
        if (t.contains("天")) return 7
        return null
    }

    internal fun parseSection(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it.coerceIn(1, 12) }
        Regex("""\d+""").find(t)?.value?.toIntOrNull()?.let { return it.coerceIn(1, 12) }
        // 圈码 ①-⑫
        val circled = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫"
        val pos = circled.indexOfFirst { t.contains(it) }
        if (pos >= 0) return pos + 1
        return null
    }
}
