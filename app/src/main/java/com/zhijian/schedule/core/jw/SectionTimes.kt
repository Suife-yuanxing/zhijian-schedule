package com.zhijian.schedule.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Duration
import java.time.LocalTime

/** 从教务页面抽出来的一个小节作息；与 Room 实体解耦，方便纯逻辑单测。 */
data class SectionTimeDraft(
    val sectionNo: Int,
    val startTime: String,
    val endTime: String,
)

/**
 * 教务页面 → 作息时间。
 *
 * 抓课表时顺带把页面上的上课时间抽出来，让用户不用再手改设置页的 12 行时间。
 * 三家后端的时间写法各不相同，这里统一成「小节序号 + 起止」：
 *
 * - 每行一小节（青果 / 树维 classic / URP 老版）：行标签是「1」「3-4」，
 *   另有一列直接写 `08:20-09:05`；
 * - 每行一大节（强智 jsxsd）：行标签是「第一大节 08:20-09:55」，
 *   时间写在这个标签里，或者是独占一列的 `08:20<br>09:55`。
 *
 * 一格里给的是一个时间段、却已知它包含 N 个小节时，按 45 分钟摊开、
 * 课间均分剩余分钟（[splitPeriod]）——这正是国内高校最常见的排法。
 */
object SectionTimes {
    /** 单小节标准时长：摊开大节时用它反推课间 */
    private const val SECTION_MINUTES = 45L

    private val TIME = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val PERIOD_LABEL = Regex("""第\s*([一二三四五六七八九十\d]{1,2})\s*大节""")
    private val RANGE_LABEL = Regex("""^(\d{1,2})\s*[-~－—]\s*(\d{1,2})\s*节""")
    private val SINGLE_LABEL = Regex("""^第?\s*(\d{1,2})\s*节?$""")

    /**
     * 把一个大节拆成 N 个小节：先各给 [SECTION_MINUTES]，剩余分钟当课间均分。
     * 总时长不够 N 个小节、或时间倒挂时返回空列表（宁可不改作息，也不写脏数据）。
     */
    fun splitPeriod(
        start: LocalTime,
        end: LocalTime,
        sectionsInPeriod: Int,
    ): List<Pair<String, String>> {
        if (sectionsInPeriod < 1) return emptyList()
        val total = Duration.between(start, end).toMinutes()
        if (total < SECTION_MINUTES * sectionsInPeriod) return emptyList()
        if (sectionsInPeriod == 1) return listOf(format(start) to format(end))

        // 课间 = 剩余分钟按 (N-1) 段均分；用秒级精度各自取整，最后一段强制对齐 end，
        // 否则「205 分钟 4 小节」这类除不尽的区间会差 1 分钟，课表上能看出错位
        val breakSeconds = (total - SECTION_MINUTES * sectionsInPeriod) * 60.0 / (sectionsInPeriod - 1)
        val out = mutableListOf<Pair<String, String>>()
        var cursor = start
        repeat(sectionsInPeriod) { i ->
            val sectionEnd = if (i == sectionsInPeriod - 1) end else cursor.plusMinutes(SECTION_MINUTES)
            out += format(cursor) to format(sectionEnd)
            cursor = sectionEnd.plusSeconds(breakSeconds.toLong())
        }
        return out
    }

    /** 从课表页 HTML 里抽作息；一行都认不出来时返回空列表，由调用方保留原作息 */
    fun fromHtml(html: String): List<SectionTimeDraft> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val found = mutableMapOf<Int, SectionTimeDraft>()
        for (row in doc.select("tr")) {
            rowSections(row)?.forEach { (no, draft) ->
                if (no in 1..MAX_SECTIONS && found[no] == null) found[no] = draft
            }
        }
        val ordered = found.values.sortedBy { it.sectionNo }
        // 时间必须随节次递进：抓到「第 2 节比第 1 节还早」说明抽错了列，整批丢弃
        if (!isMonotonic(ordered)) return emptyList()
        return ordered
    }

    /**
     * 截图 / 纯文本里的一行行时间 → 作息（截图课表导入用）。
     *
     * 一类是时段头「上午 08:20 - 11:45」，它整体套住下面的大节；
     * 另一类是大节本身「08:20-09:55」。被别的区间完整包含的区间当作外层表头丢掉，
     * 剩下的按起始时间排序后依次编大节号。
     */
    fun fromLines(lines: List<String>): List<SectionTimeDraft> {
        val ranges = mutableListOf<Pair<LocalTime, LocalTime>>()
        lines.forEach { raw ->
            val times = TIME.findAll(raw).mapNotNull { parse(it.value) }.toList()
            if (times.size >= 2 && times[0].isBefore(times[1])) {
                ranges += times[0] to times[1]
            }
        }
        if (ranges.isEmpty()) return emptyList()
        val distinct = ranges.distinct()
        // 表头是若干大节的并集：一条区间若把另一条完整包住，它就是表头而不是课节，丢掉
        val inner =
            distinct.filter { candidate ->
                distinct.none { other ->
                    other != candidate &&
                        !candidate.first.isAfter(other.first) &&
                        !candidate.second.isBefore(other.second)
                }
            }
        // 剩下的如果是整段（上午/下午/晚上表头，超过约 3 小节时长），按两个大节摊开；
        // 否则就是一个大节，按 2 小节摊开
        val periods = (inner.ifEmpty { distinct }).sortedBy { it.first }
        val out = mutableListOf<SectionTimeDraft>()
        var no = 1
        periods.forEach { (from, to) ->
            val minutes = Duration.between(from, to).toMinutes()
            val count = if (minutes > SECTION_MINUTES * 3) SECTIONS_PER_PERIOD * 2 else SECTIONS_PER_PERIOD
            splitPeriod(from, to, count).forEach { (s, e) ->
                out += SectionTimeDraft(no, s, e)
                no++
            }
        }
        return out.filter { it.sectionNo <= MAX_SECTIONS }
    }

    /**
     * 把抽到的作息补成连续 1..neededCount：
     * 优先用页面抽到的，其次用 [fallback] 的默认值，两处都没有就按上一个小节顺推。
     */
    fun normalize(
        extracted: List<SectionTimeDraft>,
        fallback: List<SectionTimeDraft>,
        neededCount: Int,
    ): List<SectionTimeDraft> {
        val byNo = extracted.associateBy { it.sectionNo }
        val fallbackByNo = fallback.associateBy { it.sectionNo }
        val count = maxOf(neededCount, extracted.size).coerceIn(1, MAX_SECTIONS)
        val out = mutableListOf<SectionTimeDraft>()
        var lastEnd: LocalTime? = null
        for (no in 1..count) {
            val hit = byNo[no] ?: fallbackByNo[no]
            if (hit != null) {
                out += SectionTimeDraft(no, hit.startTime, hit.endTime)
                lastEnd = parse(hit.endTime)
            } else {
                val start = lastEnd?.plusMinutes(BREAK_MINUTES) ?: parse(DEFAULT_START) ?: continue
                val end = start.plusMinutes(SECTION_MINUTES)
                out += SectionTimeDraft(no, format(start), format(end))
                lastEnd = end
            }
        }
        return out
    }

    /** 一行表格 → 它覆盖的若干小节；不是「节次行」时返回 null */
    private fun rowSections(row: Element): List<Pair<Int, SectionTimeDraft>>? {
        val cells = row.children().filter { it.tagName() == TD || it.tagName() == TH }
        val label = cells.firstOrNull { !isWeekdayCell(it) }
        val slot = label?.let { parseLabel(compact(it.text())) }
        val times = rowTimes(cells)
        val from = times.firstOrNull()
        val to = times.lastOrNull()
        val valid = slot != null && times.size >= 2 && from != null && to != null && from.isBefore(to)
        if (!valid) return null
        return splitPeriod(from!!, to!!, slot!!.second).mapIndexed { i, (s, e) ->
            (slot.first + i).let { no -> no to SectionTimeDraft(no, s, e) }
        }
    }

    /** 一行里的时间取值（按单元格顺序），只认 `HH:mm` 形态 */
    private fun rowTimes(cells: List<Element>): List<LocalTime> =
        cells
            .filter { !isWeekdayCell(it) }
            .flatMap { cell -> TIME.findAll(cell.text()).mapNotNull { parse(it.value) } }

    private fun isWeekdayCell(cell: Element): Boolean {
        val t = cell.text()
        return t.contains("星期") || t.contains("礼拜")
    }

    /** 行标签 → (起始小节, 含几个小节)；认不出返回 null */
    private fun parseLabel(text: String): Pair<Int, Int>? {
        PERIOD_LABEL.find(text)?.let { m ->
            val n = chineseNumber(m.groupValues[1]) ?: return@let
            return ((n - 1) * SECTIONS_PER_PERIOD + 1) to SECTIONS_PER_PERIOD
        }
        RANGE_LABEL.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            if (a !in 1..MAX_SECTIONS || b !in a..MAX_SECTIONS) return@let
            return a to (b - a + 1)
        }
        SINGLE_LABEL.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n !in 1..MAX_SECTIONS) return@let
            return n to 1
        }
        return null
    }

    /** 时间必须随节次递增，且每节起止不倒挂 */
    private fun isMonotonic(sections: List<SectionTimeDraft>): Boolean {
        var prevEnd: LocalTime? = null
        return sections.all { s ->
            val start = parse(s.startTime)
            val end = parse(s.endTime)
            val ok =
                start != null &&
                    end != null &&
                    start.isBefore(end) &&
                    (prevEnd == null || !start.isBefore(prevEnd))
            prevEnd = end
            ok
        }
    }

    private fun chineseNumber(raw: String): Int? {
        val text = raw.trim()
        text.toIntOrNull()?.let { return it }
        val value =
            when (text.length) {
                TEN.length -> if (text == TEN) TEN_VALUE else DIGITS[text.single()]
                TEN_PREFIX_LENGTH ->
                    if (text[0] == TEN_CHAR) {
                        TEN_VALUE + (DIGITS[text[1]] ?: 0)
                    } else if (text[1] == TEN_CHAR) {
                        (DIGITS[text[0]] ?: 0) * TEN_VALUE
                    } else {
                        null
                    }
                TEN_PREFIX_LENGTH + 1 ->
                    if (text[1] == TEN_CHAR) {
                        (DIGITS[text[0]] ?: 0) * TEN_VALUE + (DIGITS[text[2]] ?: 0)
                    } else {
                        null
                    }
                else -> null
            }
        return value?.takeIf { it > 0 }
    }

    private fun compact(text: String): String = text.replace(Regex("""\s+"""), "")

    /** 截图上常是「08：20」这种全角冒号，LocalTime.parse 不认，先归一化 */
    private fun parse(text: String): LocalTime? =
        runCatching { LocalTime.parse(text.trim().replace('：', ':')) }.getOrNull()

    private fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /**
     * 出厂作息：只存六个大节的起止，小节由 [splitPeriod] 算出来。
     *
     * 这样设置页、截图识别、教务抓取用的是同一套数字——
     * 不会出现「代码里抄了一份默认值，实际课表换一批」的对不上。
     */
    private val BUILT_IN_PERIODS =
        listOf(
            "08:20" to "09:55",
            "10:10" to "11:45",
            "14:20" to "15:55",
            "16:05" to "17:40",
            "18:20" to "19:55",
            "20:00" to "21:35",
        )

    /** 出厂作息；用户从任何来源（教务 / 截图 / 脚本）拿到时间后都会被覆盖 */
    fun defaults(): List<SectionTimeDraft> {
        val out = mutableListOf<SectionTimeDraft>()
        BUILT_IN_PERIODS.forEachIndexed { index, (start, end) ->
            val from = parse(start) ?: return@forEachIndexed
            val to = parse(end) ?: return@forEachIndexed
            splitPeriod(from, to, SECTIONS_PER_PERIOD).forEachIndexed { i, (s, e) ->
                out += SectionTimeDraft(index * SECTIONS_PER_PERIOD + i + 1, s, e)
            }
        }
        return out.take(MAX_SECTIONS)
    }

    const val MAX_SECTIONS = 12
    const val SECTIONS_PER_PERIOD = 2
    private const val BREAK_MINUTES = 5L
    private const val DEFAULT_START = "08:20"
    private const val TD = "td"
    private const val TH = "th"
    private const val TEN = "十"
    private const val TEN_CHAR = '十'
    private const val TEN_PREFIX_LENGTH = 2
    private const val TEN_VALUE = 10
    private val DIGITS =
        mapOf(
            '一' to 1,
            '二' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
        )
}
