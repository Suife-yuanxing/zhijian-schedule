package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.model.CourseDraft

/**
 * 各解析器共用的中间模型：解析出的课程草稿、被跳过的记录数，以及给用户看的告警。
 * 解析器只负责产出它；来源标注与导入预览由上层编排决定。
 */
data class ParseResult(
    val drafts: List<CourseDraft>,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** 无课程、只有一条告警的失败出口。 */
        fun error(message: String): ParseResult = ParseResult(emptyList(), 0, listOf(message))
    }
}
