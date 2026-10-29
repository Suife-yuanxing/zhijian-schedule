package com.zhijian.schedule.feature.importer.ocr

import com.zhijian.schedule.core.jw.SectionTimes
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.feature.importer.parsers.ParseResult
import kotlin.math.abs

/**
 * 课表截图 → 课程草稿的总编排。
 * 1) 找到“星期X”表头定位列，节次锚点（第一大节/第N节）或纵向比例定位行，按列聚类出课程块；
 * 2) 无表头时退化为“周一 3-4节”式文本行解析；
 * 3) 多张截图结果按 课程名+星期+节次+教室+教师 合并（周次取并集）。
 *
 * 文本归一化见 [OcrText]，字段识别（节次/周次/教室/教师）见 [OcrFieldParsing]，
 * 列布局与行映射见 [OcrGrid]。
 */
object OcrTimetableParser {
    /** 剩余文本按这些字符切成候选词，最长的那个当课程名 */
    private val TOKEN_SPLIT = Regex("""[\s·|,，;；]+""")

    fun parse(
        pages: List<List<OcrLine>>,
        totalWeeks: Int,
        totalSections: Int,
    ): ParseResult {
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
        // 截图上的时间轴比本地写死的作息更可信：左侧时间轴 + 时段头的所有文字都喂给抽取器
        val texts = pages.flatten().map { it.text }.filter { it.isNotBlank() }
        val sectionTimes = SectionTimes.fromLines(texts)
        return ParseResult(merged, warnings = warnings, sectionTimes = sectionTimes)
    }

    // ---------- 页面级 ----------

    private fun parsePage(
        lines: List<OcrLine>,
        weeks: Int,
        sections: Int,
        warnings: MutableList<String>,
        pageIdx: Int,
    ): List<CourseDraft> {
        val headers =
            OcrGrid.expandDayHeaders(
                lines
                    .mapNotNull { line ->
                        val t = OcrText.compact(line.text)
                        val m = OcrText.DAY_HEADER.find(t) ?: return@mapNotNull null
                        val day = OcrText.dayNumber(m.groupValues[1]) ?: return@mapNotNull null
                        if (!OcrText.restLooksLikeHeader(t.substring(m.range.last + 1))) return@mapNotNull null
                        day to line
                    }.groupBy({ it.first }, { it.second })
                    .mapValues { (_, ls) -> ls.minBy { it.top } },
            )

        val drafts =
            if (headers.size >= 3) {
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

    // ---------- 网格模式 ----------

    private fun parseGrid(
        lines: List<OcrLine>,
        headers: Map<Int, OcrLine>,
        weeks: Int,
        sections: Int,
    ): List<CourseDraft> {
        val days = headers.keys.sorted()
        val centers = days.map { headers.getValue(it).centerX }
        val colGap = if (centers.size >= 2) OcrGrid.median(centers.zipWithNext { a, b -> b - a }) else 400f
        val gridTop = headers.values.maxOf { it.bottom }
        val content = lines.filter { it.centerY > gridTop }
        if (content.isEmpty()) return emptyList()
        val layout = OcrGrid.ColumnLayout(centers, colGap)

        val anchors =
            content
                .filter { layout.columnOf(it) == null && it.centerX < layout.leftEdge }
                .mapNotNull { line ->
                    val anchor = OcrFieldParsing.sectionAnchor(line.text) ?: return@mapNotNull null
                    OcrGrid.Anchor(anchor.number, anchor.big, line.centerY)
                }.sortedBy { it.y }
                .fold(mutableListOf<OcrGrid.Anchor>()) { acc, a ->
                    if (acc.isEmpty() || abs(acc.last().y - a.y) > 4f) acc += a
                    acc
                }

        val gridBottom = content.maxOf { it.bottom }
        val rowMap = OcrGrid.RowMap.build(anchors, gridTop, gridBottom, sections)
        val gapThreshold = maxOf(OcrGrid.median(content.map { it.height }) * 1.3f, rowMap.medianRowHeight * 0.22f)

        val result = mutableListOf<CourseDraft>()
        days.forEachIndexed { idx, day ->
            val colLines = content.filter { layout.columnOf(it) == idx }.sortedBy { it.top }
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
        rowMap: OcrGrid.RowMap,
        weeks: Int,
        sections: Int,
    ): List<CourseDraft> {
        val chunks = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        block.forEach { line ->
            val t = line.text.trim()
            when {
                t.isEmpty() -> Unit
                OcrFieldParsing.SEPARATOR.matches(t) -> {
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
                val w = OcrFieldParsing.findWeeks(t, totalWeeks) ?: OcrFieldParsing.bareWeeks(t, totalWeeks)
                if (w != null) {
                    mask = w.mask
                    t = t.removeRange(w.range).trim()
                }
            }
            OcrFieldParsing.PAREN.findAll(t).forEach { m ->
                val inner = m.groupValues[1].trim()
                val sec = OcrFieldParsing.sectionText(inner)
                when {
                    OcrFieldParsing.parityOf(inner) != null -> oddParity = OcrFieldParsing.parityOf(inner)
                    OcrFieldParsing.findWeeks(inner, totalWeeks) != null ->
                        if (mask ==
                            null
                        ) {
                            mask = OcrFieldParsing.findWeeks(inner, totalWeeks)!!.mask
                        }
                    sec != null ->
                        if (start == null) {
                            start = sec.start
                            end = sec.end
                        }
                    else -> parens += inner
                }
            }
            t = OcrFieldParsing.PAREN.replace(t, " ")
            OcrFieldParsing.BRACKET_SECTIONS.find(t)?.let { m ->
                OcrFieldParsing.sectionNumbers(m.groupValues[1])?.let { (a, b) ->
                    start = a
                    end = b
                }
                t = t.removeRange(m.range)
            }
            if (start == null && t.trim().length <= 8) {
                val sec = OcrFieldParsing.sectionText(t.trim())
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
        val nameIdx = leftovers.indexOfFirst { OcrText.cleanName(it).length >= 2 }
        val nameRaw = leftovers.getOrNull(nameIdx) ?: leftovers.firstOrNull() ?: parens.firstOrNull() ?: return null
        val name = OcrText.cleanName(nameRaw)
        if (name.isBlank() || (name.length == 1 && !name[0].isLetter())) return null
        val rest = leftovers.filterIndexed { i, _ -> i != nameIdx }
        val room = rest.firstOrNull { OcrFieldParsing.looksLikeRoom(it) }?.trim() ?: ""
        val teacher =
            parens.firstOrNull { it != name && it.length <= 12 && !OcrFieldParsing.looksLikeRoom(it) }
                ?: rest.firstOrNull { it != room && it != name && OcrFieldParsing.looksLikeTeacher(it) }?.trim()
                ?: ""

        val s = (start ?: fallbackStart).coerceIn(1, totalSections)
        val e = (end ?: fallbackEnd).coerceIn(s, totalSections)
        var weekMask = mask ?: oddParity?.let { WeekMask.parity(totalWeeks, it) } ?: WeekMask.of(totalWeeks)
        if (mask != null && oddParity != null) weekMask = OcrFieldParsing.applyParity(weekMask, oddParity!!)
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

    private fun parseTextLines(
        lines: List<OcrLine>,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val ordered =
            lines
                .sortedWith(compareBy({ it.centerY }, { it.left }))
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
        val drafts = mutableListOf<CourseDraft>()
        var pendingName: String? = null
        var i = 0
        while (i < ordered.size) {
            val step = consumeLine(ordered, i, pendingName, totalWeeks)
            pendingName = step.pendingName
            step.draft?.let { drafts += it }
            i = step.nextLine
        }
        return drafts
    }

    /** 处理第 [index] 行之后应该跳到哪里、留下了什么「候选名」、有没有产出一门课 */
    private data class LineStep(
        val nextLine: Int,
        val pendingName: String?,
        val draft: CourseDraft?,
    )

    private fun consumeLine(
        ordered: List<String>,
        index: Int,
        pendingName: String?,
        totalWeeks: Int,
    ): LineStep {
        val line = ordered[index]
        val anchor = lineAnchor(line)
        // 认不出「星期 + 节次」的行：留作下一门课的候选名
        if (anchor == null) return LineStep(index + 1, line, null)
        val lookahead = lookaheadAfter(ordered, index + 1)
        val fields = collectFields(anchor, lookahead, totalWeeks)
        val name = OcrText.cleanName(fields.name.ifBlank { pendingName.orEmpty() })
        if (name.isBlank()) return LineStep(index + 1, line, null)
        val draft =
            CourseDraft(
                name = name,
                teacher = fields.teacher,
                room = fields.room,
                dayOfWeek = anchor.day,
                startSection = anchor.start,
                endSection = anchor.end,
                weekMask = fields.weekMask(totalWeeks),
            )
        // 命中一门课：这一行连同它的后续行一起消费掉
        return LineStep(index + 1 + lookahead.size, null, draft)
    }

    /** 一行里能认出的「星期 + 节次」，认不出返回 null */
    private data class LineAnchor(
        val day: Int,
        val start: Int,
        val end: Int,
        /** 去掉星期与节次之后的剩余文本 */
        val rest: String,
    )

    private fun lineAnchor(line: String): LineAnchor? {
        val compact = OcrText.compact(line)
        val dayMatch = OcrText.DAY_INLINE.find(compact)
        val day = dayMatch?.let { OcrText.dayNumber(it.groupValues[1]) }
        val sec = OcrFieldParsing.sectionText(compact)
        if (dayMatch == null || day == null || sec == null) return null
        val rest = compact.replace(dayMatch.value, " ").replace(sec.token, " ")
        return LineAnchor(day, sec.start, sec.end, rest)
    }

    /** 紧随其后、仍属于同一门课的若干行（遇到下一个「星期 + 节次」就停，最多 3 行） */
    private fun lookaheadAfter(
        ordered: List<String>,
        from: Int,
    ): List<String> {
        val collected = mutableListOf<String>()
        var j = from
        while (j < ordered.size && collected.size < 3) {
            val compact = OcrText.compact(ordered[j])
            val isNextAnchor =
                OcrText.DAY_INLINE.containsMatchIn(compact) && OcrFieldParsing.sectionText(compact) != null
            if (isNextAnchor) break
            collected += ordered[j]
            j++
        }
        return collected
    }

    /** 一门课的字段：名字、教师、教室与两种周次线索 */
    private data class LineFields(
        val name: String,
        val teacher: String,
        val room: String,
        /** 直接解析出的周次掩码 */
        val mask: String?,
        /** 括号里的「单 / 双」；与 mask 同时存在时用来过滤 */
        val parity: Boolean?,
    ) {
        /** 掩码优先；只有单双就按单双铺满整学期；都没有就是全学期 */
        fun weekMask(totalWeeks: Int): String {
            val base = mask ?: return parity?.let { WeekMask.parity(totalWeeks, it) } ?: WeekMask.of(totalWeeks)
            return parity?.let { OcrFieldParsing.applyParity(base, it) } ?: base
        }
    }

    private fun collectFields(
        anchor: LineAnchor,
        lookahead: List<String>,
        totalWeeks: Int,
    ): LineFields {
        var mask: String? = null
        var rest = anchor.rest
        val weeksInLine = weeksOf(rest, totalWeeks)
        if (weeksInLine != null) {
            mask = weeksInLine.mask
            rest = rest.removeRange(weeksInLine.range).trim()
        }
        val parens = parenFields(rest, totalWeeks, mask)
        mask = parens.mask ?: mask
        rest = OcrFieldParsing.PAREN.replace(rest, " ")
        val fromLookahead = lookaheadFields(lookahead, totalWeeks, mask)
        mask = fromLookahead.mask ?: mask
        val name =
            rest
                .split(TOKEN_SPLIT)
                .filter { it.length >= 2 }
                .maxByOrNull { it.length }
                ?.trim()
                .orEmpty()
                .ifBlank { fromLookahead.name }
        return LineFields(
            name = name,
            teacher = parens.teacher.ifEmpty { fromLookahead.teacher },
            room = fromLookahead.room,
            mask = mask,
            parity = parens.parity,
        )
    }

    /** 括号里可能是单双标记、周次或教师名 */
    private data class ParenFields(
        val mask: String?,
        val parity: Boolean?,
        val teacher: String,
    )

    private fun parenFields(
        text: String,
        totalWeeks: Int,
        knownMask: String?,
    ): ParenFields {
        var mask = knownMask
        var parity: Boolean? = null
        var teacher = ""
        OcrFieldParsing.PAREN.findAll(text).forEach { m ->
            val inner = m.groupValues[1].trim()
            val innerParity = OcrFieldParsing.parityOf(inner)
            val innerWeeks = OcrFieldParsing.findWeeks(inner, totalWeeks)
            when {
                innerParity != null -> parity = innerParity
                innerWeeks != null -> mask = mask ?: innerWeeks.mask
                teacher.isEmpty() -> teacher = inner
            }
        }
        return ParenFields(mask, parity, teacher)
    }

    /** 后续行里可能散落着周次、教室和教师 */
    private data class LookaheadFields(
        val mask: String?,
        val room: String,
        val teacher: String,
        val name: String,
    )

    private fun lookaheadFields(
        lookahead: List<String>,
        totalWeeks: Int,
        knownMask: String?,
    ): LookaheadFields {
        var mask = knownMask
        var room = ""
        var teacher = ""
        for (nl in lookahead) {
            if (mask == null) {
                val w = weeksOf(nl, totalWeeks)
                if (w != null) {
                    mask = w.mask
                    continue
                }
            }
            if (room.isEmpty() && OcrFieldParsing.looksLikeRoom(nl)) {
                room = nl.trim()
                continue
            }
            if (teacher.isEmpty() && OcrFieldParsing.looksLikeTeacher(nl)) teacher = nl.trim()
        }
        val name =
            lookahead
                .firstOrNull { cand -> isNameCandidate(cand, totalWeeks) }
                ?.trim()
                .orEmpty()
        return LookaheadFields(mask, room, teacher, name)
    }

    /** 后续行里像课程名的那一行：长度合适、不含周次、不是教室也不是教师 */
    private fun isNameCandidate(
        cand: String,
        totalWeeks: Int,
    ): Boolean =
        cand.trim().length in 2..20 &&
            OcrFieldParsing.findWeeks(cand, totalWeeks) == null &&
            !OcrFieldParsing.looksLikeRoom(cand) &&
            !OcrFieldParsing.looksLikeTeacher(cand) &&
            OcrText.cleanName(cand).isNotBlank()

    /** 文本里的周次：先试「N-M 周」写法，再试裸范围 */
    private fun weeksOf(
        text: String,
        totalWeeks: Int,
    ) = OcrFieldParsing.findWeeks(text, totalWeeks) ?: OcrFieldParsing.bareWeeks(text, totalWeeks)

    // ---------- 合并 ----------

    private fun merge(drafts: List<CourseDraft>): List<CourseDraft> {
        val out = mutableListOf<CourseDraft>()
        drafts.forEach { d ->
            val idx =
                out.indexOfFirst { o ->
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
                out[idx] =
                    o.copy(
                        room = o.room.ifBlank { d.room },
                        teacher = o.teacher.ifBlank { d.teacher },
                        weekMask = union(o.weekMask, d.weekMask),
                    )
            }
        }
        return out.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
    }

    private fun compatible(
        a: String,
        b: String,
    ): Boolean = a.isBlank() || b.isBlank() || a == b

    private fun union(
        a: String,
        b: String,
    ): String =
        String(
            CharArray(maxOf(a.length, b.length)) { i ->
                if ((i < a.length && a[i] == '1') || (i < b.length && b[i] == '1')) '1' else '0'
            },
        )
}
