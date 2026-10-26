package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.model.ImportSource
import java.time.LocalDate

/** 导入入口通道：粘贴文本与外接文件各有独立的候选解析器集合与优先级。 */
enum class ParserChannel { PASTED_TEXT, FILE }

/** 解析器声明的格式标识；[source] 供导入预览标注来源。 */
enum class TimetableFormat(val source: ImportSource) {
    ICS(ImportSource.ICS),
    XLSX(ImportSource.XLSX),
    JSON(ImportSource.JSON),
    CSV(ImportSource.CSV),
    WAKEUP(ImportSource.WAKEUP),
}

/** 解析所需的课表上下文：总周数、开学日期与作息节次；各解析器按需取用。 */
data class ParseContext(
    val totalWeeks: Int,
    val semesterStart: LocalDate? = null,
    val sections: List<SectionTimeEntity> = emptyList(),
)

/**
 * 课表解析器的统一契约：先探测（[canParse]）再解析（[parse]），产物统一为 [ParseResult]。
 * 调用方按 [TimetableParsers] 的注册顺序遍历，不再散落 if-else 判断文件格式。
 */
interface TimetableParser {

    val format: TimetableFormat

    /** 本解析器参与的入口通道；不在通道内的解析器不参与探测。 */
    val channels: Set<ParserChannel>

    /** 该输入是否像本格式；同一通道内按注册顺序取首个命中者。 */
    fun canParse(input: ParserInput): Boolean

    fun parse(input: ParserInput, context: ParseContext): ParseResult
}
