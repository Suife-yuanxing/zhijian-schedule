package com.zhijian.schedule.core.util

/**
 * 周次位掩码：字符串形式，长度 = 学期总周数，'1' 表示上课（方案 4.5）。
 * 如 20 周学期的 "1-16 周" = "11111111111111110000"。
 */
object WeekMask {

    fun of(totalWeeks: Int, include: Boolean = true): String =
        (if (include) '1' else '0').toString().repeat(totalWeeks)

    fun normalize(mask: String?, totalWeeks: Int): String {
        val m = (mask ?: "").filter { it == '0' || it == '1' }
        return when {
            m.length == totalWeeks -> m
            m.length > totalWeeks -> m.substring(0, totalWeeks)
            else -> m.padEnd(totalWeeks, '0')
        }
    }

    /** week 为 1 基 */
    fun contains(mask: String, week: Int): Boolean {
        val idx = week - 1
        return idx in mask.indices && mask[idx] == '1'
    }

    fun weeks(mask: String): List<Int> =
        mask.mapIndexedNotNull { i, c -> if (c == '1') i + 1 else null }

    fun count(mask: String): Int = mask.count { it == '1' }

    /**
     * 由周次列表构建掩码。
     */
    fun fromWeeks(totalWeeks: Int, weeks: Collection<Int>): String {
        val chars = CharArray(totalWeeks) { '0' }
        weeks.forEach { w -> if (w in 1..totalWeeks) chars[w - 1] = '1' }
        return String(chars)
    }

    /**
     * 解析可读周次表达式，支持：
     * "1-16"、"1~16周"、"1,3,5-9"、"1-16单周"、"2-18双"、"单周"/"双周"
     * @return 掩码，无法解析时返回 null
     */
    fun parseExpression(totalWeeks: Int, raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text = text.replace("，", ",").replace("－", "-").replace("~", "-").replace("—", "-")
        val odd = text.contains("单") || text.contains("奇")
        val even = text.contains("双") || text.contains("偶")
        val nums = Regex("""\d+\s*-\s*\d+|\d+""").findAll(text).map { it.value }.toList()
        if (nums.isEmpty()) {
            if (!odd && !even) return null
        }
        val weeks = mutableSetOf<Int>()
        if (nums.isEmpty()) {
            // "单周" / "双周" 无范围时视为全学期
            (1..totalWeeks).forEach { weeks += it }
        }
        nums.forEach { token ->
            if (token.contains("-")) {
                val (a, b) = token.split("-").mapNotNull { it.trim().toIntOrNull() }
                    .let { it.getOrNull(0) to it.getOrNull(1) }
                if (a != null && b != null) {
                    for (w in minOf(a, b)..maxOf(a, b)) weeks += w
                }
            } else {
                token.trim().toIntOrNull()?.let { weeks += it }
            }
        }
        if (odd || even) {
            weeks.retainAll { w -> if (odd) w % 2 == 1 else w % 2 == 0 }
        }
        if (weeks.isEmpty()) return null
        return fromWeeks(totalWeeks, weeks)
    }

    fun parity(totalWeeks: Int, odd: Boolean): String =
        fromWeeks(totalWeeks, (1..totalWeeks).filter { if (odd) it % 2 == 1 else it % 2 == 0 })

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
}
