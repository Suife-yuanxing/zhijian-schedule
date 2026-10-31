package com.zhijian.schedule.core.util

/**
 * 周次位掩码：字符串形式，长度 = 学期总周数，'1' 表示上课（方案 4.5）。
 * 如 20 周学期的 "1-16 周" = "11111111111111110000"。
 */
object WeekMask {
    /** 周次表达式里的数字片段：「3-5」或单点「7」 */
    private val NUMBER_TOKEN = Regex("""\d+\s*-\s*\d+|\d+""")

    fun of(
        totalWeeks: Int,
        include: Boolean = true,
    ): String = (if (include) '1' else '0').toString().repeat(totalWeeks)

    fun normalize(
        mask: String?,
        totalWeeks: Int,
    ): String {
        val m = (mask ?: "").filter { it == '0' || it == '1' }
        return when {
            m.length == totalWeeks -> m
            m.length > totalWeeks -> m.substring(0, totalWeeks)
            else -> m.padEnd(totalWeeks, '0')
        }
    }

    /** week 为 1 基 */
    fun contains(
        mask: String,
        week: Int,
    ): Boolean {
        val idx = week - 1
        return idx in mask.indices && mask[idx] == '1'
    }

    fun weeks(mask: String): List<Int> = mask.mapIndexedNotNull { i, c -> if (c == '1') i + 1 else null }

    fun count(mask: String): Int = mask.count { it == '1' }

    /**
     * 由周次列表构建掩码。
     */
    fun fromWeeks(
        totalWeeks: Int,
        weeks: Collection<Int>,
    ): String {
        val chars = CharArray(totalWeeks) { '0' }
        weeks.forEach { w -> if (w in 1..totalWeeks) chars[w - 1] = '1' }
        return String(chars)
    }

    /**
     * 解析可读周次表达式，支持：
     * "1-16"、"1~16周"、"1,3,5-9"、"1-16单周"、"2-18双"、"单周"/"双周"
     * @return 掩码，无法解析时返回 null
     */
    fun parseExpression(
        totalWeeks: Int,
        raw: String?,
    ): String? {
        val text = normalizeExpression(raw)
        val odd = text.contains("单") || text.contains("奇")
        val even = text.contains("双") || text.contains("偶")
        val tokens = NUMBER_TOKEN.findAll(text).map { it.value }.toList()
        // 既没有数字也没有「单/双」：不是周次表达式
        if (tokens.isEmpty() && !odd && !even) return null
        val weeks = collectWeeks(totalWeeks, tokens)
        if (odd || even) {
            weeks.retainAll { w -> if (odd) w % 2 == 1 else w % 2 == 0 }
        }
        return weeks.takeIf { it.isNotEmpty() }?.let { fromWeeks(totalWeeks, it) }
    }

    /** 全角标点与各种连字符统一成半角，让后面的正则只需要认一种写法 */
    private fun normalizeExpression(raw: String?): String =
        raw
            ?.trim()
            .orEmpty()
            .replace("，", ",")
            .replace("－", "-")
            .replace("~", "-")
            .replace("—", "-")

    /** 逐段展开成周次集合；没有任何数字的「单周 / 双周」按全学期算 */
    private fun collectWeeks(
        totalWeeks: Int,
        tokens: List<String>,
    ): MutableSet<Int> {
        if (tokens.isEmpty()) return (1..totalWeeks).toMutableSet()
        val weeks = mutableSetOf<Int>()
        tokens.forEach { token -> weeks += expandToken(token) }
        return weeks
    }

    /** "3-5" 展开成 3..5（两端写反也能兜住），"7" 只取 7 */
    private fun expandToken(token: String): List<Int> {
        if (!token.contains("-")) return listOfNotNull(token.trim().toIntOrNull())
        val edges = token.split("-").mapNotNull { it.trim().toIntOrNull() }
        val from = edges.getOrNull(0) ?: return emptyList()
        val to = edges.getOrNull(1) ?: return emptyList()
        return (minOf(from, to)..maxOf(from, to)).toList()
    }

    fun parity(
        totalWeeks: Int,
        odd: Boolean,
    ): String = fromWeeks(totalWeeks, (1..totalWeeks).filter { if (odd) it % 2 == 1 else it % 2 == 0 })

    /**
     * 掩码转可读表达式："1-4,6,8-10"；全选返回 "全周"。
     */
    fun toExpression(mask: String): String {
        val ws = weeks(mask)
        if (ws.isEmpty()) return "—"
        if (ws.size == mask.length) return "全周"
        val sb = StringBuilder()
        var i = 0
        while (i < ws.size) {
            var j = i
            while (j + 1 < ws.size && ws[j + 1] == ws[j] + 1) j++
            if (j - i >= 2) {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(ws[i]).append('-').append(ws[j])
            } else {
                for (k in i..j) {
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append(ws[k])
                }
            }
            i = j + 1
        }
        sb.append("周")
        return sb.toString()
    }

    /**
     * 掩码转「人话」：等差为 2 的纯单周/纯双周折叠成「x-y 单/双周」，
     * 其余一律走 [toExpression]（全选→「全周」、空→「—」）。
     * 供课程编辑器的实时周次预览；不改动 [toExpression] 的既有行为。
     */
    fun describe(mask: String): String {
        val ws = weeks(mask)
        if (ws.isEmpty()) return "—"
        if (ws.size == mask.length) return "全周"
        if (ws.size == 1) return toExpression(mask)
        val step2 = ws.zipWithNext().all { (a, b) -> b - a == 2 }
        val odd = ws.first() % 2 == 1
        return when {
            step2 && odd -> "${ws.first()}-${ws.last()} 单周"
            step2 && !odd -> "${ws.first()}-${ws.last()} 双周"
            else -> toExpression(mask)
        }
    }
}
