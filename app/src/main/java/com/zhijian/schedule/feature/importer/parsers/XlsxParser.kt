package com.zhijian.schedule.feature.importer.parsers

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * 轻量 xlsx 读取：直接解 zip 读取 sheet XML（sharedStrings + 首个 worksheet），
 * 避免引入 Apache POI 的体积与 Android 兼容问题。支持 t="s"/inlineStr/str/数值。
 */
object XlsxParser {

    fun parse(input: InputStream, totalWeeks: Int): TableMapping.Result {
        val entries = readEntries(input)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetKey = entries.keys
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .sorted()
            .firstOrNull()
            ?: return TableMapping.Result(emptyList(), 0, listOf("未找到工作表"))
        val rows = parseSheet(entries.getValue(sheetKey), sharedStrings)
        return TableMapping.map(rows, totalWeeks)
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name == "xl/sharedStrings.xml" ||
                    (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                ) {
                    result[name] = zip.readBytes()
                }
            }
        }
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inSi = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> { inSi = true; current.clear() }
                        "t" -> if (inSi) current.append(parser.nextText() ?: "")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inSi) {
                    strings += current.toString()
                    inSi = false
                }
            }
            event = parser.next()
        }
        return strings
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var cells: MutableList<String>? = null
        var colIndex = -1
        var cellType: String? = null
        var cellValue: String? = null
        var inlineText: String? = null
        var inInline = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> cells = mutableListOf()
                    "c" -> {
                        colIndex = columnIndex(parser.getAttributeValue(null, "r"))
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = null
                        inlineText = null
                    }
                    "v" -> cellValue = parser.nextText()
                    "is" -> inInline = true
                    "t" -> if (inInline) inlineText = (inlineText ?: "") + (parser.nextText() ?: "")
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "is" -> inInline = false
                    "c" -> {
                        val list = cells
                        if (list != null && colIndex >= 0) {
                            while (list.size <= colIndex) list.add("")
                            val value = when (cellType) {
                                "s" -> cellValue?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "inlineStr" -> inlineText ?: ""
                                else -> cellValue ?: ""
                            }
                            list[colIndex] = value.trim()
                        }
                    }
                    "row" -> {
                        cells?.let { rows += it.toList() }
                        cells = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** "B12" → 1（0 基列号） */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var value = 0
        for (ch in ref) {
            if (ch.isLetter()) {
                value = value * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else break
        }
        return value - 1
    }
}
