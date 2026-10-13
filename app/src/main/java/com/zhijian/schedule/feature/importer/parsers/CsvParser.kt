package com.zhijian.schedule.feature.importer.parsers

/**
 * CSV / 制表符文本解析（支持引号转义），并按表头映射为课程。
 */
object CsvParser {

    fun parse(text: String, totalWeeks: Int): TableMapping.Result {
        val cleaned = text.removePrefix("\uFEFF")
        val delimiter = detectDelimiter(cleaned)
        val rows = parseRows(cleaned, delimiter)
        return TableMapping.map(rows, totalWeeks)
    }

    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val comma = firstLine.count { it == ',' }
        val tab = firstLine.count { it == '\t' }
        val semicolon = firstLine.count { it == ';' }
        return when {
            tab >= comma && tab >= semicolon -> '\t'
            semicolon > comma -> ';'
            else -> ','
        }
    }

    fun parseRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text
        while (i < s.length) {
            val ch = s[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                        field.append('"'); i++
                    }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    current += field.toString(); field.clear()
                }
                ch == '\r' -> Unit
                ch == '\n' -> {
                    current += field.toString(); field.clear()
                    rows += current.toList(); current.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current += field.toString()
            rows += current.toList()
        }
        return rows
    }
}
