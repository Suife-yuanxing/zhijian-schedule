package com.zhijian.schedule.feature.importer.parsers

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 轻量 xlsx 读取：直接解 zip 读取 sheet XML（sharedStrings + 首个 worksheet），
 * 避免引入 Apache POI 的体积与 Android 兼容问题。支持 t="s"/inlineStr/str/数值。
 */
object XlsxParser : TimetableParser {
    override val format = TimetableFormat.XLSX

    override val channels = setOf(ParserChannel.FILE)

    override fun canParse(input: ParserInput): Boolean = input.isZip

    override fun parse(
        input: ParserInput,
        context: ParseContext,
    ): ParseResult {
        val bytes = requireNotNull(input.bytes) { "xlsx 解析需要文件字节" }
        return parse(ByteArrayInputStream(bytes), context.totalWeeks)
    }

    /** 直连入口：xlsx 字节流 + 总周数。 */
    fun parse(
        input: InputStream,
        totalWeeks: Int,
    ): ParseResult {
        val entries = readEntries(input)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetKey =
            entries.keys
                .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
                .sorted()
                .firstOrNull()
                ?: return ParseResult.error("未找到工作表")
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
                XmlPullParser.START_TAG -> inSi = startSharedString(parser, inSi, current)
                XmlPullParser.END_TAG ->
                    if (parser.name == "si" && inSi) {
                        strings += current.toString()
                        inSi = false
                    }
            }
            event = parser.next()
        }
        return strings
    }

    /** 共享字符串的 START_TAG：`<si>` 开一段、`<t>` 追加文本。返回新的 inSi 状态 */
    private fun startSharedString(
        parser: XmlPullParser,
        inSi: Boolean,
        current: StringBuilder,
    ): Boolean =
        when (parser.name) {
            "si" -> {
                current.clear()
                true
            }
            "t" -> {
                if (inSi) current.append(parser.nextText() ?: "")
                inSi
            }
            else -> inSi
        }

    private fun parseSheet(
        bytes: ByteArray,
        shared: List<String>,
    ): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val scan = SheetScan(shared)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> scan.onStartTag(parser)
                XmlPullParser.END_TAG -> scan.onEndTag(parser.name)
            }
            event = parser.next()
        }
        return scan.rows
    }

    /** 一次工作表扫描的可变状态：行缓冲 + 当前单元格中间态 */
    private class SheetScan(
        private val shared: List<String>,
    ) {
        val rows = mutableListOf<List<String>>()
        private var cells: MutableList<String>? = null
        private var state = CellState()

        fun onStartTag(parser: XmlPullParser) {
            if (parser.name == "row") cells = mutableListOf()
            state = state.onStartTag(parser)
        }

        fun onEndTag(name: String) {
            when (name) {
                "is" -> state = state.copy(inInline = false)
                "c" -> cells?.let { state.writeInto(it, shared) }
                "row" -> {
                    cells?.let { rows += it.toList() }
                    cells = null
                }
            }
        }
    }

    /** 当前 `<c>` / `<v>` / `<is>` 的中间状态：解析过程中会被逐标签改写 */
    private data class CellState(
        val colIndex: Int = -1,
        val cellType: String? = null,
        val cellValue: String? = null,
        val inlineText: String? = null,
        val inInline: Boolean = false,
    ) {
        /** START_TAG 状态转移；`<c>` 开新单元格、`<v>/<t>` 收文本、`<is>` 进入内联串 */
        fun onStartTag(parser: XmlPullParser): CellState =
            when (parser.name) {
                "c" ->
                    CellState(
                        colIndex = columnIndex(parser.getAttributeValue(null, "r")),
                        cellType = parser.getAttributeValue(null, "t"),
                        // inInline 原样带过来：`<c>` 只重置本格内容，不该改动内联串状态
                        inInline = inInline,
                    )
                "v" -> copy(cellValue = parser.nextText())
                "is" -> copy(inInline = true)
                "t" -> if (inInline) copy(inlineText = (inlineText ?: "") + (parser.nextText() ?: "")) else this
                else -> this
            }

        /** `</c>`：把本格的文本按类型取出来补进行（列号可能跨越空列，先补齐） */
        fun writeInto(
            cells: MutableList<String>,
            shared: List<String>,
        ) {
            if (colIndex < 0) return
            while (cells.size <= colIndex) cells.add("")
            cells[colIndex] = textOf(shared).trim()
        }

        private fun textOf(shared: List<String>): String =
            when (cellType) {
                "s" -> cellValue?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                "inlineStr" -> inlineText ?: ""
                else -> cellValue ?: ""
            }
    }

    /** "B12" → 1（0 基列号） */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var value = 0
        for (ch in ref) {
            if (ch.isLetter()) {
                value = value * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return value - 1
    }
}
