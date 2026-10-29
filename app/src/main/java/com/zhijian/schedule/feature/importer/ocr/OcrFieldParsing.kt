package com.zhijian.schedule.feature.importer.ocr

import com.zhijian.schedule.core.util.WeekMask

/**
 * OCR 字段识别：从一行文本或一个括号片段里认出节次、周次、教室与教师。
 */
internal object OcrFieldParsing {
    internal val SECTION_ANCHOR = Regex("""^第?([一二三四五六七八九十]{1,3}|\d{1,2})(大)?节""")
    internal val BARE_NUM = Regex("""(\d{1,2})""")
    internal val SECTION_RANGE = Regex("""(\d{1,2})\s*[-~－—]\s*(\d{1,2})\s*节""")
    internal val BRACKET_SECTIONS = Regex("""[\[【](\d{1,2}(?:\s*[-~－—]\s*\d{1,2})*)\s*节[\]】]""")
    internal val LINE_SECTIONS =
        Regex("""^第?([一二三四五六七八九十]{1,3}|\d{1,2})\s*[-~－—]\s*第?([一二三四五六七八九十]{1,3}|\d{1,2})\s*节$""")
    internal val WEEK_RE =
        Regex(
            """(\d{1,2}(?:\s*[-~－—]\s*\d{1,2})?""" +
                """(?:\s*[,，、]\s*\d{1,2}(?:\s*[-~－—]\s*\d{1,2})?)*)""" +
                """\s*[单双奇偶]?\s*[（(【\[]?\s*[周週][）)】\]]?""",
        )
    internal val BARE_WEEKS = Regex("""^第?\s*(\d{1,2})\s*[-~－—]\s*(\d{1,2})\s*(?!节)[^\d\s]{0,2}$""")
    internal val PAREN = Regex("""[（(]([^）)]{1,20})[）)]""")
    internal val SEPARATOR = Regex("""^[-—_~·.\s]{3,}$""")
    internal val ROOM_HINT = Regex("""(教室|教学楼|楼|室|馆|场|机房|实验室|实训|校区)""")
    internal val ROOM_CODE = Regex("""[A-Za-z0-9][A-Za-z0-9\-]{1,7}""")

    /**
     * 行首节次锚点：「第N大节」或纯数字行；[SectionAnchor.big] 标记大节写法（一 = 第 1-2 节）。
     * 不是锚点返回 null。
     */
    internal fun sectionAnchor(text: String): SectionAnchor? {
        val t = OcrText.compact(text)
        val m = SECTION_ANCHOR.find(t)
        val n =
            if (m != null) {
                OcrText.chineseNumber(m.groupValues[1])
            } else {
                BARE_NUM
                    .matchEntire(t)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
        n ?: return null
        return SectionAnchor(n, m?.groupValues?.get(2)?.isNotEmpty() == true)
    }

    internal data class SectionAnchor(
        val number: Int,
        val big: Boolean,
    )

    /**
     * 从一段文本里找周次；命中优先级：显式「N-M 周」段 → 「全周 / 每周」→ 「周次：…」整行。
     */
    fun findWeeks(
        text: String,
        totalWeeks: Int,
    ): WeekMatch? =
        weekByRange(text, totalWeeks)
            ?: wholeSemesterWeek(text, totalWeeks)
            ?: weekByCountLine(text, totalWeeks)

    /** 文中的「1-16」「1,3,5-9」(+ 单双) + 「周」 */
    private fun weekByRange(
        text: String,
        totalWeeks: Int,
    ): WeekMatch? {
        val m = WEEK_RE.find(text) ?: return null
        val base = m.groupValues[1].replace(" ", "")
        // 单双标记可能落在匹配段之外（"1-16 周(单)"），前后各借一个字符再看
        val window = text.substring(maxOf(0, m.range.first - 1), minOf(text.length, m.range.last + 2))
        val parity =
            when {
                window.contains('单') || window.contains('奇') -> "单"
                window.contains('双') || window.contains('偶') -> "双"
                else -> ""
            }
        val mask = WeekMask.parseExpression(totalWeeks, base + parity + "周") ?: return null
        return WeekMatch(m.range, mask)
    }

    /** 「全周 / 每周」：整个学期都上 */
    private fun wholeSemesterWeek(
        text: String,
        totalWeeks: Int,
    ): WeekMatch? {
        for (token in listOf("全周", "每周")) {
            val i = text.indexOf(token)
            if (i >= 0) return WeekMatch(i until i + token.length, WeekMask.of(totalWeeks))
        }
        return null
    }

    /** 「周次：1-16」这种整行描述：把整行当表达式解析 */
    private fun weekByCountLine(
        text: String,
        totalWeeks: Int,
    ): WeekMatch? {
        if (!text.contains("周次")) return null
        val mask = WeekMask.parseExpression(totalWeeks, text) ?: return null
        return WeekMatch(0 until text.length, mask)
    }

    fun parityOf(s: String): Boolean? =
        when (s.trim()) {
            "单", "奇", "单周" -> true
            "双", "偶", "双周" -> false
            else -> null
        }

    fun applyParity(
        mask: String,
        odd: Boolean,
    ): String =
        String(CharArray(mask.length) { i -> if (mask[i] == '1' && (i + 1) % 2 == (if (odd) 1 else 0)) '1' else '0' })

    fun looksLikeRoom(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s.length > 16) return false
        if (ROOM_HINT.containsMatchIn(s)) return true
        val code = s.replace(" ", "")
        if (!ROOM_CODE.matches(code)) return false
        val letters = code.count { it.isLetter() }
        val digits = code.count { it.isDigit() }
        return letters > 0 || digits >= 3
    }

    fun looksLikeTeacher(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 2..5) return false
        if (s.any { it.isDigit() }) return false
        if (looksLikeRoom(s)) return false
        return s.all { it in '\u4e00'..'\u9fff' || it == '·' }
    }

    fun sectionNumbers(group: String): Pair<Int, Int>? {
        val nums = Regex("""\d{1,2}""").findAll(group).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return null
        return nums.first() to nums.last()
    }

    class SectionToken(
        val token: String,
        val start: Int,
        val end: Int,
    )

    /** 从文本中提取节次：支持 "3-4节" / "[01-02节]" / "第3-4节" / "第3节" */
    fun sectionText(text: String): SectionToken? {
        val c = OcrText.compact(text)
        SECTION_RANGE.find(c)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return SectionToken(m.value, a, b)
        }
        BRACKET_SECTIONS.find(c)?.let { m ->
            sectionNumbers(m.groupValues[1])?.let { (a, b) -> return SectionToken(m.value, a, b) }
        }
        LINE_SECTIONS.find(c)?.let { m ->
            val a = OcrText.chineseNumber(m.groupValues[1]) ?: return null
            val b = OcrText.chineseNumber(m.groupValues[2]) ?: return null
            return SectionToken(m.value, a, b)
        }
        return null
    }

    data class WeekMatch(
        val range: IntRange,
        val mask: String,
    )

    /** OCR 常漏读“周”字（"1-16周"→"1-16ā"）：纯数字号段行按周次处理，且要求号段在学期范围内 */
    fun bareWeeks(
        text: String,
        totalWeeks: Int,
    ): WeekMatch? {
        val t = text.trim()
        val m = BARE_WEEKS.matchEntire(t) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        if (a < 1 || b < a || b > totalWeeks) return null
        return WeekMatch(0 until t.length, WeekMask.fromWeeks(totalWeeks, (a..b).toList()))
    }
}
