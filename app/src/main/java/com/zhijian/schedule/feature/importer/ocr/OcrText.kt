package com.zhijian.schedule.feature.importer.ocr

/**
 * OCR 文本归一化：空白与全角数字收敛成可比较的形式，星期 / 节次的中文写法转成数字。
 */
internal object OcrText {

    internal val DAY_HEADER = Regex("""^(?:星期|周|礼拜)([一二三四五六日天1-7])""")
    internal val DAY_INLINE = Regex("""(?:星期|周|礼拜)([一二三四五六日天1-7])""")

    fun cleanName(raw: String): String =
        raw.trim()
            .trim('-', '·', '•', ':', '：', '|')
            .replace(Regex("""[（(]\s*[）)]"""), "")
            .trim()

    fun compact(s: String): String {
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

    fun restLooksLikeHeader(rest: String): Boolean =
        rest.isEmpty() || rest.all { it.isDigit() || it in "./月日号-—~·()（）" }

    fun dayNumber(token: String): Int? = when (token) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        "日", "天", "7" -> 7
        else -> null
    }

    fun chineseNumber(s: String): Int? {
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
}
