package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.model.CourseDraft

/**
 * 各解析器共用的中间模型：解析出的课程草稿、被跳过的记录数，以及给用户看的告警。
 * 解析器只负责产出它；来源标注与导入预览由上层编排决定。
 *
 * [sectionTimes] 是源文件/截图里自己带的上课时间（截图时间轴、日历事件起止）。
 * 非空表示「这份数据比本地作息更权威」，导入时要跟着课表一起更新作息。
 */
data class ParseResult(
    val drafts: List<CourseDraft>,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
    val sectionTimes: List<SectionTimeDraft> = emptyList(),
) {
    companion object {
        /** 无课程、只有一条告警的失败出口。 */
        fun error(message: String): ParseResult = ParseResult(emptyList(), 0, listOf(message))
    }
}
