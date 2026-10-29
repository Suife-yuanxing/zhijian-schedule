package com.zhijian.schedule.feature.importer.parsers

/** 解析结果与命中的解析器，供调用方按格式映射导入来源。 */
data class ParsedTimetable(
    val format: TimetableFormat,
    val result: ParseResult,
)

/**
 * 解析器注册表：注册顺序即探测优先级，与历史派发顺序逐一对应。
 *
 * 文件通道：ICS → XLSX → JSON → CSV；
 * 文本通道：ICS → 口令（口令内部再依次尝试 JSON / 表格 / Base64）。
 * 两个通道各有一个兜底解析器（表格 / 口令），因此 [select] 必定命中。
 */
object TimetableParsers {
    private val FALLBACKS =
        mapOf(
            ParserChannel.FILE to CsvParser,
            ParserChannel.PASTED_TEXT to WakeUpParser,
        )

    val all: List<TimetableParser> =
        listOf(
            IcsParser,
            XlsxParser,
            JsonParser,
            CsvParser,
            WakeUpParser,
        )

    fun candidates(channel: ParserChannel): List<TimetableParser> = all.filter { channel in it.channels }

    /** 通道内首个 canParse 命中的解析器；全部不命中时回退到该通道的兜底解析器。 */
    fun select(
        input: ParserInput,
        channel: ParserChannel,
    ): TimetableParser = candidates(channel).firstOrNull { it.canParse(input) } ?: FALLBACKS.getValue(channel)

    fun parse(
        input: ParserInput,
        context: ParseContext,
        channel: ParserChannel,
    ): ParsedTimetable {
        val parser = select(input, channel)
        return ParsedTimetable(parser.format, parser.parse(input, context))
    }
}
