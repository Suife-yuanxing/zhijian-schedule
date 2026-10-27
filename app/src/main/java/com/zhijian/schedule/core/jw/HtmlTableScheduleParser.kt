package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 通用 HTML 课表表格解析器。
 *
 * 面向「星期 × 节次」的大表格：青果 jwweb、老 URP、树维 classic EAMS、
 * 以及学校自建的课表页。不依赖厂商专用 id，靠表头文字与单元格文本识别。
 *
 * 已收进这里的差异：
 * - 表头可能在前 3 行的任意一行，可能是 th 也可能是 td；
 * - rowspan/colspan 会把星期列挤位，先展开成规整网格；
 * - 单元格内多门课用 <br> / <hr> 分隔；
 * - 周次写法：1-16周、1-16周(单)、1,3,5周；节次写法：[01-02节]、第3-4节。
 */
@Suppress("TooManyFunctions")
object HtmlTableScheduleParser {
    private const val MIN_SECTION = 1
    private const val MAX_SECTION = 12
    private const val MAX_COLSPAN = 12
    private const val MAX_ROWSPAN = 30
    private const val MAX_COLS = 40
    private const val MAX_DAY_TEXT = 8
    private const val MAX_NAME_LENGTH = 40
    private const val DAYS_PER_WEEK = 7
    private const val DEFAULT_SPAN = 1
    private const val HEADER_SCAN_ROWS = 3
    private const val MIN_DAY_COLUMNS = 3

    /** 行标签列的「纯数字节次」写法，如 1、3-4 */
    private val PURE_SECTION = Regex("""^(\d{1,2})(?:\s*[-~—]\s*(\d{1,2}))?$""")

    private data class Cell(
        val html: String,
        /** rowspan 的起始行：同一门课在展开后的每一行都复制，但节次要从起始行算 */
        val originRow: Int,
        val span: Int,
    )

    private data class Layout(
        val table: Element,
        val headerRow: Int,
        /** 列索引 → 星期（1..7） */
        val days: Map<Int, Int>,
    )

    fun parse(
        html: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val layout = pickLayout(doc) ?: return emptyList()
        val grid = expand(layout.table)
        if (grid.isEmpty()) return emptyList()

        val drafts = mutableListOf<CourseDraft>()
        grid.forEachIndexed { rowIndex, cells ->
            if (rowIndex == layout.headerRow) return@forEachIndexed
            layout.days.forEach { (col, day) ->
                val cell = cells.getOrNull(col) ?: return@forEach
                if (isBlankCell(cell.html)) return@forEach
                drafts += parseCell(cell.html, day, rowRangeOf(grid, cell, layout.days), totalWeeks)
            }
        }
        return merge(drafts)
    }

    /**
     * 行标签列的节次区间：从 rowspan 起始行读到覆盖的最后一行
     * （树维 `1` + `2` 两行合并 ⇒ 1-2 节；青果 `1-2节` + `3-4节` ⇒ 1-4 节）。
     */
    private fun rowRangeOf(
        grid: List<Array<Cell?>>,
        cell: Cell,
        days: Map<Int, Int>,
    ): IntRange? {
        val start = labelAt(grid, cell.originRow, days)?.let { parseRowLabel(it) } ?: return null
        val lastRow = cell.originRow + cell.span - 1
        if (lastRow <= cell.originRow) return start
        val end = labelAt(grid, lastRow, days)?.let { parseRowLabel(it) }
        return if (end == null) start else start.first..maxOf(start.last, end.last)
    }

    /** 取某一行「不属于星期列的最左非空单元格」文本，即节次标签列 */
    private fun labelAt(
        grid: List<Array<Cell?>>,
        rowIndex: Int,
        days: Map<Int, Int>,
    ): String? {
        val cells = grid.getOrNull(rowIndex) ?: return null
        val col =
            cells.indices.firstOrNull { col ->
                col !in days && !cells[col]?.html.isNullOrBlank()
            } ?: return null
        return plain(cells[col]?.html.orEmpty())
    }

    // ---------- 表格定位与网格展开 ----------

    private fun pickLayout(doc: Document): Layout? {
        var best: Layout? = null
        for (table in doc.select("table")) {
            val header = headerRow(table.select("tr")) ?: continue
            val current = best
            if (current == null || header.second.size > current.days.size) {
                best = Layout(table, header.first, header.second)
            }
        }
        return best
    }

    private fun headerRow(rows: List<Element>): Pair<Int, Map<Int, Int>>? {
        for (i in 0 until minOf(HEADER_SCAN_ROWS, rows.size)) {
            val days = dayColumns(cellsOf(rows[i]))
            if (days.size >= MIN_DAY_COLUMNS) return i to days
        }
        return null
    }

    private fun dayColumns(cells: List<Element>): Map<Int, Int> {
        val days = LinkedHashMap<Int, Int>()
        var col = 0
        for (cell in cells) {
            val span = colspan(cell)
            val day = parseDay(plain(cell.text()))
            if (day != null) {
                // 表头 colspan（如「星期一」横跨上午/下午）要把每一列都映射到同一天
                for (dc in 0 until span) {
                    days[col + dc] = day
                }
            }
            col += span
        }
        return days
    }

    private fun cellsOf(row: Element): List<Element> {
        val children = row.children()
        return children.filter { it.tagName() == "td" || it.tagName() == "th" }
    }

    private fun colspan(cell: Element): Int {
        val raw = cell.attr("colspan").trim().toIntOrNull()
        return raw?.coerceIn(DEFAULT_SPAN, MAX_COLSPAN) ?: DEFAULT_SPAN
    }

    private fun rowspan(cell: Element): Int {
        val raw = cell.attr("rowspan").trim().toIntOrNull()
        return raw?.coerceIn(DEFAULT_SPAN, MAX_ROWSPAN) ?: DEFAULT_SPAN
    }

    /** 把 rowspan/colspan 展开成规整二维网格；同格内容复制到被合并的每个位置 */
    private fun expand(table: Element): List<Array<Cell?>> {
        val rows = table.select("tr").filter { cellsOf(it).isNotEmpty() }
        if (rows.isEmpty()) return emptyList()
        val cellRows = rows.map { cellsOf(it) }
        val maxCols =
            cellRows
                .maxOfOrNull { row -> row.sumOf { colspan(it) } }
                ?.coerceIn(MIN_SECTION, MAX_COLS)
                ?: return emptyList()

        val grid = MutableList(rows.size) { arrayOfNulls<Cell>(maxCols) }
        val taken = MutableList(rows.size) { BooleanArray(maxCols) }

        cellRows.forEachIndexed { r, cells ->
            var col = 0
            for (cell in cells) {
                while (col < maxCols && taken[r][col]) col++
                if (col >= maxCols) break
                val rs = rowspan(cell).coerceAtMost(rows.size - r)
                val cs = colspan(cell).coerceAtMost(maxCols - col)
                val entry = Cell(cell.html(), r, rs)
                for (dr in 0 until rs) {
                    for (dc in 0 until cs) {
                        val rr = r + dr
                        val cc = col + dc
                        if (rr < rows.size && cc < maxCols) {
                            grid[rr][cc] = entry
                            taken[rr][cc] = true
                        }
                    }
                }
                col += cs
            }
        }
        return grid
    }

    // ---------- 单元格解析 ----------

    private fun parseCell(
        raw: String,
        day: Int,
        rowRange: IntRange?,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val drafts = mutableListOf<CourseDraft>()
        for (block in splitBlocks(raw)) {
            val draft = parseBlock(block, day, rowRange, totalWeeks)
            if (draft != null) drafts += draft
        }
        return drafts
    }

    /** 先按 <hr> 切块，再按 <br> 切行 */
    private fun splitBlocks(raw: String): List<List<String>> {
        val marked = raw.replace(Regex("(?i)<hr[^>]*>"), "<br>@@SPLIT@@<br>")
        val body = Jsoup.parseBodyFragment(marked).body().html()
        val blocks = mutableListOf<List<String>>()
        for (part in body.split("@@SPLIT@@")) {
            val lines =
                part
                    .split(Regex("(?i)<br\\s*/?>"))
                    .map { line -> plain(line) }
                    .filter { it.isNotEmpty() }
            if (lines.isNotEmpty()) blocks += lines
        }
        return blocks
    }

    private fun parseBlock(
        lines: List<String>,
        day: Int,
        rowRange: IntRange?,
        totalWeeks: Int,
    ): CourseDraft? {
        val nameLine = lines.firstOrNull { isCourseName(it) } ?: return null
        val tokens = stripMeta(nameLine).split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstToken = tokens.firstOrNull()
        val name = firstToken?.trim()?.trimEnd('*', '（', '(').orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        val inlineOthers = tokens.drop(1)

        val rest = lines.filter { it != nameLine }
        val weekLine = rest.firstOrNull { isWeekLine(it) }
        val mask =
            weekLine?.let { WeekMask.parseExpression(totalWeeks, it) }
                ?: WeekMask.of(totalWeeks)
        val sections = firstSection(rest) ?: parseSections(nameLine) ?: rowRange
        return sections?.let { range ->
            val others =
                (rest.filter { it != weekLine && parseSections(it) == null } + inlineOthers)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != name }
            val room = others.firstOrNull { looksLikeRoom(it) }.orEmpty()
            val teacher =
                others
                    .firstOrNull {
                        it != room &&
                            it.length <= MAX_SECTION &&
                            !it.contains("周") &&
                            !looksLikeRoom(it)
                    }.orEmpty()
            CourseDraft(
                name = name,
                teacher = teacher,
                room = room,
                dayOfWeek = day,
                startSection = range.first.coerceIn(MIN_SECTION, MAX_SECTION),
                endSection = range.last.coerceIn(MIN_SECTION, MAX_SECTION),
                weekMask = mask,
            )
        }
    }

    /**
     * 行标签列：树维 classic 在行首只写「1」「3-4」「第3-4节」，
     * 青果/URP 写「[01-02节]」。纯数字（或纯数字区间）直接当节次起点。
     */
    private fun parseRowLabel(text: String): IntRange? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val pure = PURE_SECTION.find(t)
        return if (pure == null) {
            parseSections(t)
        } else {
            val a = pure.groupValues[1].toIntOrNull()
            val b = pure.groupValues[2].toIntOrNull() ?: a
            if (a == null || b == null || a !in MIN_SECTION..MAX_SECTION) {
                null
            } else {
                minOf(a, b)..maxOf(a, b)
            }
        }
    }

    private fun firstSection(lines: List<String>): IntRange? {
        for (line in lines) {
            val range = parseSections(line)
            if (range != null) return range
        }
        return null
    }

    // ---------- 文本识别 ----------

    private fun isBlankCell(raw: String): Boolean {
        val t = plain(raw)
        return t.isEmpty() || t.all { it == '-' || it == '—' || it == '_' || it.isWhitespace() }
    }

    private fun isCourseName(text: String): Boolean {
        val t = text.trim()
        val shortSectionLabel = parseSections(t) != null && t.length <= MAX_SECTION
        return t.isNotEmpty() &&
            t.length <= MAX_NAME_LENGTH &&
            !isWeekLine(t) &&
            !shortSectionLabel &&
            !t.matches(Regex("""[\d\s\-.,，、()（）单双周节\[\]]+"""))
    }

    private fun isWeekLine(text: String): Boolean {
        if (text.contains("星期")) return false
        if (Regex("""\d{1,2}\s*[-~—]\s*\d{1,2}\s*周""").containsMatchIn(text)) return true
        return text.contains("周") && Regex("""\d""").containsMatchIn(text)
    }

    private fun stripMeta(text: String): String =
        text
            .replace(Regex("""\d{1,2}\s*[-~—]\s*\d{1,2}\s*周\s*[（(]?\s*[单双]?\s*[）)]?"""), " ")
            .replace(Regex("""\d{1,2}\s*[,，、]\s*\d{1,2}(?:\s*[,，、]\s*\d{1,2})*\s*周"""), " ")
            .replace(Regex("""\[?\s*\d{1,2}\s*[-~—]\s*\d{1,2}\s*节\s*\]?"""), " ")
            .replace(Regex("""第\s*[一二三四五六七八九十\d]+\s*(?:大)?节"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun parseSections(text: String): IntRange? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val range = Regex("""(\d{1,2})\s*[-~—]\s*(\d{1,2})\s*节""").find(t)
        val single = Regex("""第\s*(\d{1,2})\s*节""").find(t)
        return when {
            range != null -> {
                val a = range.groupValues[1].toIntOrNull()
                val b = range.groupValues[2].toIntOrNull()
                if (a == null || b == null) null else minOf(a, b)..maxOf(a, b)
            }
            single != null -> single.groupValues[1].toIntOrNull()?.let { n -> n..n }
            else -> null
        }
    }

    private fun parseDay(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_DAY_TEXT) return null
        if (!t.contains("星期") && !t.contains("周")) return null
        val zh = listOf("一", "二", "三", "四", "五", "六", "日", "天")
        val hit = zh.indexOfFirst { t.contains(it) }
        return if (hit >= 0) (hit + 1).coerceAtMost(DAYS_PER_WEEK) else null
    }

    private fun looksLikeRoom(text: String): Boolean {
        if (text.contains("楼") || text.contains("室")) return true
        if (text.contains("馆") || text.contains("场")) return true
        if (text.contains("机房") || text.contains("实验")) return true
        if (text.contains("中心") || text.contains("校区") || text.contains("教")) return true
        return Regex("""^[A-Za-z]?\d{2,4}(?:-\d+)?$""").matches(text)
    }

    private fun plain(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        return doc.text().replace('\u00A0', ' ').trim()
    }

    // ---------- 合并 ----------

    private fun merge(drafts: List<CourseDraft>): List<CourseDraft> {
        val merged = LinkedHashMap<String, CourseDraft>()
        for (draft in drafts) {
            val key =
                listOf(
                    draft.name,
                    draft.dayOfWeek,
                    draft.startSection,
                    draft.endSection,
                    draft.teacher,
                    draft.room,
                ).joinToString("|")
            val old = merged[key]
            merged[key] =
                if (old == null) {
                    draft
                } else {
                    old.copy(weekMask = orMask(old.weekMask, draft.weekMask))
                }
        }
        return merged.values.toList()
    }

    private fun orMask(
        a: String,
        b: String,
    ): String {
        val length = maxOf(a.length, b.length)
        val chars =
            CharArray(length) { i ->
                if (a.getOrNull(i) == '1' || b.getOrNull(i) == '1') '1' else '0'
            }
        return String(chars)
    }
}
