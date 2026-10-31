package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask

/**
 * 经典 EAMS（金智 / 树维系 `courseTable*!courseTable.action` 页面）的
 * 内嵌 JS 课表解析器。
 *
 * 这一类页面里的 HTML 表格 `#manualArrangeCourseTable` 是空壳，数据全在
 * 页内 JS 块里（多源交叉验证过的固定形态）：
 * ```
 * var unitCount = 12;
 * var actTeachers = [{id:7396,name:"张三",lab:true}];
 * activity = new TaskActivity(actTeacherName.join(','), "MATH101", "高等数学",
 *                            "R101", "教1-101", "0111111111111111100000", ...);
 * index = 2 * unitCount + 3;            // D=0 基星期、P=0 基节次；也兼容 index = 27; 纯数字
 * ```
 *
 * 关键规则：
 * - 周次位图下标 0 是占位符，下标 i=1 即第 i 周；位图超过 20 位且含 8/9 时
 *   按十进制整数位掩码展开（超长学期部署）；
 * - unitCount 从页面拿（12/13/11 每校不一），拿不到就不猜——返回空列表；
 * - 教师 "-1"、教室/课名「停课」是教务自己的停课标记，跳过；
 * - 课名含逗号，参数切分必须带引号/括号深度状态机，不能裸 split(',')。
 */
internal object EamsTaskParser {
    private const val UNIT_COUNT_MIN = 8

    /** 教师回查窗口：actTeachers 数组可能离活动块有一段距离 */
    private const val TEACHER_LOOKBACK = 2500
    private const val WIDE_BITMAP = 20
    private const val MAX_BITMAP_WEEKS = 63
    private const val ARG_TEACHER = 1
    private const val ARG_NAME = 3
    private const val ARG_ROOM = 5
    private const val ARG_BITMAP = 6

    private val UNIT_COUNT = Regex("""\bvar\s+unitCount\s*=\s*(\d+)\s*;""")
    private val ACTIVITY = Regex("""new\s+TaskActivity\(""")
    private val INDEX =
        Regex("""index\s*=\s*(?:(\d+)\s*\*\s*(?:unitCount|\d+)\s*\+\s*(\d+)|(\d+))\s*;""")
    private val ACT_TEACHERS = Regex("""var\s+actTeachers\s*=\s*\[([^\]]*)\]\s*;""")
    private val TEACHER_NAME = Regex("""name\s*:\s*"([^"]*)"""")
    private val NAME_VAR = Regex("""var\s+courseNameLessonNo\s*=\s*"([^"]*)"\s*;""")

    /** 活动块：TaskActivity 参数 + 它名下的全部 index 赋值 */
    private data class Activity(
        val teacher: String,
        val name: String,
        val room: String,
        val weeks: List<Int>,
        val start: Int,
        val nameExpr: String?,
    )

    /** 解析整页 HTML；没有 unitCount 或一个活动块都没有时返回空列表 */
    fun parse(
        html: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        if (!html.contains("TaskActivity")) return emptyList()
        val unitCount =
            UNIT_COUNT
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: return emptyList()
        if (unitCount < UNIT_COUNT_MIN) return emptyList()
        return splitActivities(html, unitCount)
            .mapNotNull { (activity, indexPairs) ->
                draftOf(activity, indexPairs, totalWeeks, html)
            }
    }

    private fun splitActivities(
        source: String,
        unitCount: Int,
    ): List<Pair<Activity, List<Pair<Int, Int>>>> {
        val marks = ACTIVITY.findAll(source).toList()
        val out = mutableListOf<Pair<Activity, List<Pair<Int, Int>>>>()
        for ((i, mark) in marks.withIndex()) {
            val (args, _) = splitArgs(source, mark.range.last + 1)
            val weeks = if (args.size > ARG_BITMAP) bitmapToWeeks(unquote(args[ARG_BITMAP])) else emptyList()
            if (weeks.isEmpty()) continue
            val nameArg = args[ARG_NAME].trim()
            val activity =
                Activity(
                    teacher = resolveTeacher(args[ARG_TEACHER], mark.range.first, source),
                    name = if (nameArg.isQuoted()) unquote(nameArg) else "",
                    room = unquote(args[ARG_ROOM]).trim(),
                    weeks = weeks,
                    start = mark.range.first,
                    nameExpr = if (nameArg.isQuoted()) null else nameArg,
                )
            val blockEnd = if (i + 1 < marks.size) marks[i + 1].range.first else source.length
            val pairs =
                INDEX
                    .findAll(source.substring(mark.range.last + 1, blockEnd))
                    .mapNotNull { m -> indexPairOf(m, unitCount) }
                    .toList()
            if (pairs.isNotEmpty()) out += activity to pairs
        }
        return out
    }

    private fun draftOf(
        activity: Activity,
        indexPairs: List<Pair<Int, Int>>,
        totalWeeks: Int,
        source: String,
    ): CourseDraft? {
        if (activity.teacher == "-1" || activity.teacher.isBlank()) return null
        if (activity.room == "停课") return null
        val name = resolveName(activity, source)
        if (name.isBlank()) return null
        val (day, start, end) = mergeNodes(indexPairs) ?: return null
        return CourseDraft(
            name = name,
            teacher = activity.teacher,
            room = activity.room,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            weekMask = WeekMask.fromWeeks(totalWeeks, activity.weeks),
        )
    }

    /** 老部署第 4 参可能是 this.courseNameLessonNo 表达式 → 回查整页的 var 定义 */
    private fun resolveName(
        activity: Activity,
        source: String,
    ): String {
        activity.name.takeIf { it.isNotBlank() }?.let { return it }
        if (activity.nameExpr?.contains("courseNameLessonNo") != true) return ""
        return NAME_VAR
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    }

    private fun resolveTeacher(
        arg: String,
        blockStart: Int,
        source: String,
    ): String {
        val trimmed = arg.trim()
        if (trimmed.isQuoted() || !trimmed.contains("join(")) return unquote(trimmed)
        val segStart = (blockStart - TEACHER_LOOKBACK).coerceAtLeast(0)
        val seg = source.substring(segStart, blockStart)
        val array =
            ACT_TEACHERS
                .findAll(seg)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?: return ""
        return TEACHER_NAME
            .findAll(array)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    /** index 赋值 → (0 基星期, 0 基节次)；纯数字形态按 unitCount 分解 */
    private fun indexPairOf(
        match: MatchResult,
        unitCount: Int,
    ): Pair<Int, Int>? {
        val day = match.groupValues[1].toIntOrNull()
        val node = match.groupValues[2].toIntOrNull()
        if (day != null && node != null) return day to node
        val linear = match.groupValues[3].toIntOrNull() ?: return null
        return linear / unitCount to linear % unitCount
    }

    /**
     * 带引号/括号深度状态机的参数切分：课名里的逗号、表达式里的括号都不切分。
     * 返回 (参数列表, 闭括号位置)。
     */
    private fun splitArgs(
        src: String,
        start: Int,
    ): Pair<List<String>, Int> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var depth = 0
        var i = start
        var closed = -1
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '"' && !isEscaped(src, i) -> {
                    inQuote = !inQuote
                    current.append(ch)
                }
                ch == '(' && !inQuote -> {
                    depth++
                    current.append(ch)
                }
                ch == ')' && !inQuote -> {
                    if (depth == 0) {
                        closed = i
                        break
                    }
                    depth--
                    current.append(ch)
                }
                ch == ',' && !inQuote && depth == 0 -> {
                    args += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotBlank() || args.isNotEmpty()) args += current.toString().trim()
        return args to closed
    }

    private fun isEscaped(
        src: String,
        i: Int,
    ): Boolean {
        var back = 0
        var j = i - 1
        while (j >= 0 && src[j] == '\\') {
            back++
            j--
        }
        return back % 2 == 1
    }

    /** 位图 → 周列表；下标 0 占位，i=1 即第 i 周；超长位图按整数位掩码展开 */
    private fun bitmapToWeeks(bitmap: String): List<Int> {
        val raw = bitmap.trim()
        val wide = raw.length > WIDE_BITMAP && raw.any { it == '8' || it == '9' }
        val value = if (wide) raw.toLongOrNull() else null
        return when {
            value != null -> (1..MAX_BITMAP_WEEKS).filter { value and (1L shl (it - 1)) != 0L }
            raw.isEmpty() -> emptyList()
            else -> raw.mapIndexedNotNull { i, ch -> if (i >= 1 && ch == '1') i else null }
        }
    }

    private fun mergeNodes(pairs: List<Pair<Int, Int>>): Triple<Int, Int, Int>? {
        if (pairs.isEmpty()) return null
        val sorted = pairs.sortedWith(compareBy({ it.first }, { it.second }))
        val day = sorted.first().first + 1
        val nodes = sorted.map { it.second + 1 }.distinct().sorted()
        var start = nodes.first()
        var end = nodes.first()
        for (n in nodes.drop(1)) {
            if (n == end + 1) end = n else break
        }
        return Triple(day, start, end)
    }

    private fun unquote(arg: String): String {
        val t = arg.trim()
        if (t.isQuoted()) return t.substring(1, t.length - 1)
        return t
    }

    private fun String.isQuoted(): Boolean =
        length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))
}
