package com.zhijian.schedule.feature.importer.ocr

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlin.math.abs

/** 一行 OCR 文本及其在图片中的包围盒（像素坐标）。 */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/**
 * 课表截图 → 课程草稿。
 * 1) 找到“星期X”表头定位列，节次锚点（第一大节/第N节）或纵向比例定位行，按列聚类出课程块；
 * 2) 无表头时退化为“周一 3-4节”式文本行解析；
 * 3) 多张截图结果按 课程名+星期+节次+教室+教师 合并（周次取并集）。
 */
object OcrTimetableParser {

    data class Result(
        val drafts: List<CourseDraft>,
        val warnings: List<String> = emptyList(),
    )

    private val DAY_HEADER = Regex("""^(?:星期|周|礼拜)([一二三四五六日天1-7])""")
    private val DAY_INLINE = Regex("""(?:星期|周|礼拜)([一二三四五六日天1-7])""")
    private val SECTION_ANCHOR = Regex("""^第?([一二三四五六七八九十]{1,3}|\d{1,2})(大)?节""")
    private val BARE_NUM = Regex("""(\d{1,2})""")
    private val SECTION_RANGE = Regex("""(\d{1,2})\s*[-~－—]\s*(\d{1,2})\s*节""")
    private val BRACKET_SECTIONS = Regex("""[\[【](\d{1,2}(?:\s*[-~－—]\s*\d{1,2})*)\s*节[\]】]""")
    private val LINE_SECTIONS = Regex("""^第?([一二三四五六七八九十]{1,3}|\d{1,2})\s*[-~－—]\s*第?([一二三四五六七八九十]{1,3}|\d{1,2})\s*节$""")
    private val WEEK_RE = Regex("""(\d{1,2}(?:\s*[-~－—]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-~－—]\s*\d{1,2})?)*)\s*[单双奇偶]?\s*[（(【\[]?\s*[周週][）)】\]]?""")
    private val BARE_WEEKS = Regex("""^第?\s*(\d{1,2})\s*[-~－—]\s*(\d{1,2})\s*(?!节)[^\d\s]{0,2}$""")
    private val PAREN = Regex("""[（(]([^）)]{1,20})[）)]""")
    private val SEPARATOR = Regex("""^[-—_~·.\s]{3,}$""")
    private val ROOM_HINT = Regex("""(教室|教学楼|楼|室|馆|场|机房|实验室|实训|校区)""")
    private val ROOM_CODE = Regex("""[A-Za-z0-9][A-Za-z0-9\-]{1,7}""")

    fun parse(pages: List<List<OcrLine>>, totalWeeks: Int, totalSections: Int): Result {
        val weeks = totalWeeks.coerceAtLeast(1)
        val sections = totalSections.coerceAtLeast(1)
        val drafts = mutableListOf<CourseDraft>()
        val warnings = mutableListOf<String>()
        pages.forEachIndexed { idx, page ->
            val lines = page.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) return@forEachIndexed
            drafts += parsePage(lines, weeks, sections, warnings, idx)
        }
        val merged = merge(drafts)
        if (merged.isEmpty() && warnings.isEmpty()) warnings += "没有识别出课程"
        return Result(merged, warnings)
    }

    // ---------- 页面级 ----------

    private fun parsePage(
        lines: List<OcrLine>,
        weeks: Int,
        sections: Int,
        warnings: MutableList<String>,
        pageIdx: Int,
    ): List<CourseDraft> {
        val headers = expandDayHeaders(
            lines.mapNotNull { line ->
                val t = compact(line.text)
                val m = DAY_HEADER.find(t) ?: return@mapNotNull null
                val day = dayNumber(m.groupValues[1]) ?: return@mapNotNull null
                if (!restLooksLikeHeader(t.substring(m.range.last + 1))) return@mapNotNull null
                day to line
            }.groupBy({ it.first }, { it.second }).mapValues { (_, ls) -> ls.minBy { it.top } }
        )

        val drafts = if (headers.size >= 3) {
            parseGrid(lines, headers, weeks, sections)
        } else {
            parseTextLines(lines, weeks)
        }
        if (drafts.isEmpty()) {
            warnings += "第 ${pageIdx + 1} 张图片没有解析出课程"
        } else if (headers.size < 3) {
            warnings += "第 ${pageIdx + 1} 张图片没识别到星期表头，已按文本行解析，请重点核对"
        }
        return drafts
    }

    /** OCR 漏读某天表头（如“星期二”→"星期"）时，按列间距与日期序号插值补出虚拟表头 */
    private fun expandDayHeaders(headers: Map<Int, OcrLine>): Map<Int, OcrLine> {
        val days = headers.keys.sorted()
        if (days.size < 2) return headers
        val centers = days.map { headers.getValue(it).centerX }
        val colGap = median(centers.zipWithNext { a, b -> b - a })
        if (colGap <= 0f) return headers
        val full = LinkedHashMap<Int, OcrLine>()
        days.forEachIndexed { i, day ->
            val line = headers.getValue(day)
            full[day] = line
            if (i == days.size - 1) return@forEachIndexed
            val nextDay = days[i + 1]
            val numGap = nextDay - day
            if (numGap < 2) return@forEachIndexed
            val nextLine = headers.getValue(nextDay)
            val pxGap = nextLine.centerX - line.centerX
            if (abs(pxGap / colGap - numGap) > 0.55f) return@forEachIndexed
            for (k in 1 until numGap) {
                val cx = line.centerX + pxGap * k / numGap
                full[day + k] = OcrLine("", cx - 0.5f, line.top, cx + 0.5f, line.bottom)
            }
        }
        return full
    }

    // ---------- 网格模式 ----------

    private fun parseGrid(
        lines: List<OcrLine>,
        headers: Map<Int, OcrLine>,
        weeks: Int,
        sections: Int,
    ): List<CourseDraft> {
        val days = headers.keys.sorted()
        val centers = days.map { headers.getValue(it).centerX }
        val colGap = if (centers.size >= 2) median(centers.zipWithNext { a, b -> b - a }) else 400f
        val gridTop = headers.values.maxOf { it.bottom }
        val content = lines.filter { it.centerY > gridTop }
        if (content.isEmpty()) return emptyList()
        val leftEdge = centers.first() - colGap / 2f
        val rightEdge = centers.last() + colGap / 2f

        fun columnOf(line: OcrLine): Int? {
            if (line.centerX < leftEdge || line.centerX > rightEdge) return null
            var best = -1
            var bestOverlap = 0f
            centers.forEachIndexed { i, c ->
                val overlap = minOf(line.right, c + colGap / 2f) - maxOf(line.left, c - colGap / 2f)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = i
                }
            }
            return if (best >= 0) best else null
        }

        val anchors = content
            .filter { columnOf(it) == null && it.centerX < leftEdge }
            .mapNotNull { line ->
                val t = compact(line.text)
                val m = SECTION_ANCHOR.find(t)
                val n = if (m != null) chineseNumber(m.groupValues[1]) else BARE_NUM.matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()
                n ?: return@mapNotNull null
                Anchor(n, m?.groupValues?.get(2)?.isNotEmpty() == true, line.centerY)
            }
            .sortedBy { it.y }
            .fold(mutableListOf<Anchor>()) { acc, a ->
                if (acc.isEmpty() || abs(acc.last().y - a.y) > 4f) acc += a
                acc
            }

        val gridBottom = content.maxOf { it.bottom }
        val rowMap = RowMap.build(anchors, gridTop, gridBottom, sections)
        val gapThreshold = maxOf(median(content.map { it.height }) * 1.3f, rowMap.medianRowHeight * 0.22f)

        val result = mutableListOf<CourseDraft>()
        days.forEachIndexed { idx, day ->
            val colLines = content.filter { columnOf(it) == idx }.sortedBy { it.top }
            if (colLines.isEmpty()) return@forEachIndexed
            var block = mutableListOf(colLines.first())
            fun flush() {
                if (block.isEmpty()) return
                result += parseBlock(block, day, rowMap, weeks, sections)
                block = mutableListOf()
            }
            for (k in 1 until colLines.size) {
                if (colLines[k].top - block.last().bottom > gapThreshold) flush()
                block += colLines[k]
            }
            flush()
        }
        return result
    }

    private fun parseBlock(
        block: List<OcrLine>,
        day: Int,
        rowMap: RowMap,
        weeks: Int,
        sections: Int,
    ): List<CourseDraft> {
        val chunks = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        block.forEach { line ->
            val t = line.text.trim()
            when {
                t.isEmpty() -> Unit
                SEPARATOR.matches(t) -> {
                    if (cur.isNotEmpty()) chunks += cur
                    cur = mutableListOf()
                }
                else -> cur += t
            }
        }
        if (cur.isNotEmpty()) chunks += cur
        val start = rowMap.rangeAt(block.first().centerY).first
        val end = rowMap.rangeAt(block.last().centerY).last
        return chunks.mapNotNull { parseChunk(it, day, start, end, weeks, sections) }
    }

    private fun parseChunk(
        texts: List<String>,
        day: Int,
        fallbackStart: Int,
        fallbackEnd: Int,
        totalWeeks: Int,
        totalSections: Int,
    ): CourseDraft? {
        var start: Int? = null
        var end: Int? = null
        var mask: String? = null
        var oddParity: Boolean? = null
        val leftovers = mutableListOf<String>()
        val parens = mutableListOf<String>()

        texts.forEach { raw ->
            var t = raw.trim()
            if (t.isEmpty()) return@forEach

            if (mask == null) {
                val w = findWeeks(t, totalWeeks) ?: bareWeeks(t, totalWeeks)
                if (w != null) {
                    mask = w.mask
                    t = t.removeRange(w.range).trim()
                }
            }
            PAREN.findAll(t).forEach { m ->
                val inner = m.groupValues[1].trim()
                val sec = sectionText(inner)
                when {
                    parityOf(inner) != null -> oddParity = parityOf(inner)
                    findWeeks(inner, totalWeeks) != null -> if (mask == null) mask = findWeeks(inner, totalWeeks)!!.mask
                    sec != null -> if (start == null) {
                        start = sec.start
                        end = sec.end
                    }
                    else -> parens += inner
                }
            }
            t = PAREN.replace(t, " ")
            BRACKET_SECTIONS.find(t)?.let { m ->
                sectionNumbers(m.groupValues[1])?.let { (a, b) ->
                    start = a
                    end = b
                }
                t = t.removeRange(m.range)
            }
            if (start == null && t.trim().length <= 8) {
                val sec = sectionText(t.trim())
                if (sec != null) {
                    start = sec.start
                    end = sec.end
                    t = ""
                }
            }
            t = t.trim()
            if (t.isNotEmpty()) leftovers += t
        }

        if (leftovers.isEmpty() && parens.isEmpty()) return null
        val nameIdx = leftovers.indexOfFirst { cleanName(it).length >= 2 }
        val nameRaw = leftovers.getOrNull(nameIdx) ?: leftovers.firstOrNull() ?: parens.firstOrNull() ?: return null
        val name = cleanName(nameRaw)
        if (name.isBlank() || (name.length == 1 && !name[0].isLetter())) return null
        val rest = leftovers.filterIndexed { i, _ -> i != nameIdx }
        val room = rest.firstOrNull { looksLikeRoom(it) }?.trim() ?: ""
        val teacher = parens.firstOrNull { it != name && it.length <= 12 && !looksLikeRoom(it) }
            ?: rest.firstOrNull { it != room && it != name && looksLikeTeacher(it) }?.trim()
            ?: ""

        val s = (start ?: fallbackStart).coerceIn(1, totalSections)
        val e = (end ?: fallbackEnd).coerceIn(s, totalSections)
        var weekMask = mask ?: oddParity?.let { WeekMask.parity(totalWeeks, it) } ?: WeekMask.of(totalWeeks)
        if (mask != null && oddParity != null) weekMask = applyParity(weekMask, oddParity!!)
        return CourseDraft(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startSection = s,
            endSection = e,
            weekMask = weekMask,
        )
    }

    // ---------- 文本回退模式 ----------

    private fun parseTextLines(lines: List<OcrLine>, totalWeeks: Int): List<CourseDraft> {
        val ordered = lines.sortedWith(compareBy({ it.centerY }, { it.left }))
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        val drafts = mutableListOf<CourseDraft>()
        var pendingName: String? = null
        var i = 0
        while (i < ordered.size) {
            val line = ordered[i]
            val c = compact(line)
            val dm = DAY_INLINE.find(c)
            val day = dm?.let { dayNumber(it.groupValues[1]) }
            val sec = sectionText(c)
            if (day != null && sec != null) {
                var rest = c.replace(dm.value, " ").replace(sec.token, " ")
                var mask: String? = null
                val tokenMatch = findWeeks(rest, totalWeeks) ?: bareWeeks(rest, totalWeeks)
                if (tokenMatch != null) {
                    mask = tokenMatch.mask
                    rest = rest.removeRange(tokenMatch.range).trim()
                }
                var teacher = ""
                var oddParity: Boolean? = null
                PAREN.findAll(rest).forEach { m ->
                    val inner = m.groupValues[1].trim()
                    when {
                        parityOf(inner) != null -> oddParity = parityOf(inner)
                        findWeeks(inner, totalWeeks) != null -> if (mask == null) mask = findWeeks(inner, totalWeeks)!!.mask
                        else -> if (teacher.isEmpty()) teacher = inner
                    }
                }
                rest = PAREN.replace(rest, " ")
                var room = ""
                val tokens = rest.split(Regex("""[\s·|,，;；]+""")).filter { it.isNotBlank() }
                var name = tokens.filter { it.length >= 2 }.maxByOrNull { it.length }?.trim().orEmpty()

                var j = i + 1
                val lookahead = mutableListOf<String>()
                while (j < ordered.size && lookahead.size < 3) {
                    val nc = compact(ordered[j])
                    if (DAY_INLINE.containsMatchIn(nc) && sectionText(nc) != null) break
                    lookahead += ordered[j]
                    j++
                }
                for (nl in lookahead) {
                    if (mask == null) {
                        val w = findWeeks(nl, totalWeeks) ?: bareWeeks(nl, totalWeeks)
                        if (w != null) {
                            mask = w.mask
                            continue
                        }
                    }
                    if (room.isEmpty() && looksLikeRoom(nl)) {
                        room = nl.trim()
                        continue
                    }
                    if (teacher.isEmpty() && looksLikeTeacher(nl)) teacher = nl.trim()
                }
                if (name.isBlank()) {
                    name = lookahead.firstOrNull { cand ->
                        cand.trim().length in 2..20 &&
                            findWeeks(cand, totalWeeks) == null &&
                            !looksLikeRoom(cand) &&
                            !looksLikeTeacher(cand) &&
                            cleanName(cand).isNotBlank()
                    }?.trim() ?: pendingName.orEmpty()
                }
                val cleaned = cleanName(name)
                if (cleaned.isNotBlank()) {
                    var weekMask = mask ?: oddParity?.let { WeekMask.parity(totalWeeks, it) } ?: WeekMask.of(totalWeeks)
                    if (mask != null && oddParity != null) weekMask = applyParity(weekMask, oddParity!!)
                    drafts += CourseDraft(
                        name = cleaned,
                        teacher = teacher,
                        room = room,
                        dayOfWeek = day,
                        startSection = sec.start,
                        endSection = sec.end,
                        weekMask = weekMask,
                    )
                    pendingName = null
                    i = j
                    continue
                }
            }
            pendingName = line
            i++
        }
        return drafts
    }

    // ---------- 合并 ----------

    private fun merge(drafts: List<CourseDraft>): List<CourseDraft> {
        val out = mutableListOf<CourseDraft>()
        drafts.forEach { d ->
            val idx = out.indexOfFirst { o ->
                o.name == d.name &&
                    o.dayOfWeek == d.dayOfWeek &&
                    o.startSection == d.startSection &&
                    o.endSection == d.endSection &&
                    compatible(o.room, d.room) &&
                    compatible(o.teacher, d.teacher)
            }
            if (idx < 0) {
                out += d
            } else {
                val o = out[idx]
                out[idx] = o.copy(
                    room = o.room.ifBlank { d.room },
                    teacher = o.teacher.ifBlank { d.teacher },
                    weekMask = union(o.weekMask, d.weekMask),
                )
            }
        }
        return out.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
    }

    private fun compatible(a: String, b: String): Boolean = a.isBlank() || b.isBlank() || a == b

    private fun union(a: String, b: String): String =
        String(CharArray(maxOf(a.length, b.length)) { i ->
            if ((i < a.length && a[i] == '1') || (i < b.length && b[i] == '1')) '1' else '0'
        })

    // ---------- 字段启发式 ----------

    private fun findWeeks(text: String, totalWeeks: Int): WeekMatch? {
        WEEK_RE.find(text)?.let { m ->
            val base = m.groupValues[1].replace(" ", "")
            val window = text.substring(maxOf(0, m.range.first - 1), minOf(text.length, m.range.last + 2))
            val parity = when {
                window.contains('单') || window.contains('奇') -> "单"
                window.contains('双') || window.contains('偶') -> "双"
                else -> ""
            }
            WeekMask.parseExpression(totalWeeks, base + parity + "周")?.let { return WeekMatch(m.range, it) }
        }
        for (token in listOf("全周", "每周")) {
            val i = text.indexOf(token)
            if (i >= 0) return WeekMatch(i until i + token.length, WeekMask.of(totalWeeks))
        }
        if (text.contains("周次")) {
            WeekMask.parseExpression(totalWeeks, text)?.let { return WeekMatch(0 until text.length, it) }
        }
        return null
    }

    private fun parityOf(s: String): Boolean? = when (s.trim()) {
        "单", "奇", "单周" -> true
        "双", "偶", "双周" -> false
        else -> null
    }

    private fun applyParity(mask: String, odd: Boolean): String =
        String(CharArray(mask.length) { i -> if (mask[i] == '1' && (i + 1) % 2 == (if (odd) 1 else 0)) '1' else '0' })

    private fun looksLikeRoom(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s.length > 16) return false
        if (ROOM_HINT.containsMatchIn(s)) return true
        val code = s.replace(" ", "")
        if (!ROOM_CODE.matches(code)) return false
        val letters = code.count { it.isLetter() }
        val digits = code.count { it.isDigit() }
        return letters > 0 || digits >= 3
    }

    private fun looksLikeTeacher(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 2..5) return false
        if (s.any { it.isDigit() }) return false
        if (looksLikeRoom(s)) return false
        return s.all { it in '\u4e00'..'\u9fff' || it == '·' }
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .trim('-', '·', '•', ':', '：', '|')
            .replace(Regex("""[（(]\s*[）)]"""), "")
            .trim()

    private fun compact(s: String): String {
        val sb = StringBuilder(s.length)
        s.forEach { c ->
            when {
                c.isWhitespace() -> Unit
                c.code in 0xFF10..0xFF19 -> sb.append('0' + (c.code - 0xFF10))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun restLooksLikeHeader(rest: String): Boolean =
        rest.isEmpty() || rest.all { it.isDigit() || it in "./月日号-—~·()（）" }

    private fun dayNumber(token: String): Int? = when (token) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        "日", "天", "7" -> 7
        else -> null
    }

    private fun chineseNumber(s: String): Int? {
        s.trim().toIntOrNull()?.let { return it }
        val digits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        return when {
            s == "十" -> 10
            s.length == 2 && s[0] == '十' -> 10 + (digits[s[1]] ?: return null)
            s.length == 2 && s[1] == '十' -> (digits[s[0]] ?: return null) * 10
            s.length == 3 && s[1] == '十' -> ((digits[s[0]] ?: return null)) * 10 + (digits[s[2]] ?: return null)
            s.length == 1 -> digits[s[0]]
            else -> null
        }
    }

    private fun sectionNumbers(group: String): Pair<Int, Int>? {
        val nums = Regex("""\d{1,2}""").findAll(group).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return null
        return nums.first() to nums.last()
    }

    private class SectionToken(val token: String, val start: Int, val end: Int)

    /** 从文本中提取节次：支持 "3-4节" / "[01-02节]" / "第3-4节" / "第3节" */
    private fun sectionText(text: String): SectionToken? {
        val c = compact(text)
        SECTION_RANGE.find(c)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return SectionToken(m.value, a, b)
        }
        BRACKET_SECTIONS.find(c)?.let { m ->
            sectionNumbers(m.groupValues[1])?.let { (a, b) -> return SectionToken(m.value, a, b) }
        }
        LINE_SECTIONS.find(c)?.let { m ->
            val a = chineseNumber(m.groupValues[1]) ?: return null
            val b = chineseNumber(m.groupValues[2]) ?: return null
            return SectionToken(m.value, a, b)
        }
        return null
    }

    private data class WeekMatch(val range: IntRange, val mask: String)

    /** OCR 常漏读“周”字（"1-16周"→"1-16ā"）：纯数字号段行按周次处理，且要求号段在学期范围内 */
    private fun bareWeeks(text: String, totalWeeks: Int): WeekMatch? {
        val t = text.trim()
        val m = BARE_WEEKS.matchEntire(t) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        if (a < 1 || b < a || b > totalWeeks) return null
        return WeekMatch(0 until t.length, WeekMask.fromWeeks(totalWeeks, (a..b).toList()))
    }

    private data class Anchor(val n: Int, val big: Boolean, val y: Float)

    private class RowMap(private val ranges: List<IntRange>, private val edges: List<Float>) {

        val medianRowHeight: Float
            get() = if (edges.size >= 2) median(edges.zipWithNext { a, b -> b - a }) else 100f

        fun rangeAt(y: Float): IntRange {
            if (ranges.isEmpty()) return 1..1
            var idx = 0
            for (k in edges.indices) {
                if (y >= edges[k]) idx = k else break
            }
            return ranges[idx.coerceIn(0, ranges.size - 1)]
        }

        companion object {
            fun build(anchors: List<Anchor>, gridTop: Float, gridBottom: Float, totalSections: Int): RowMap {
                if (anchors.size >= 2) {
                    val big = anchors.any { it.big } || anchors.maxOf { it.n } * 2 <= totalSections + 1
                    val ranges = anchors.map { a ->
                        if (big) (a.n * 2 - 1)..minOf(a.n * 2, totalSections)
                        else a.n.coerceIn(1, totalSections).let { it..it }
                    }
                    val edges = mutableListOf<Float>()
                    edges += gridTop
                    for (k in 1 until anchors.size) edges += (anchors[k - 1].y + anchors[k].y) / 2f
                    edges += gridBottom
                    return RowMap(ranges, edges)
                }
                val rows = (totalSections + 1) / 2
                val span = (gridBottom - gridTop).coerceAtLeast(1f)
                val ranges = (0 until rows).map { i -> (i * 2 + 1)..minOf(i * 2 + 2, totalSections) }
                val edges = (0..rows).map { i -> gridTop + span * i / rows }
                return RowMap(ranges, edges)
            }
        }
    }
}

private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
