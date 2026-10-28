package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft

/**
 * 一次教务抓取的完整结果：课程 + 页面上写的上课时间。
 *
 * 作息时间不再由代码写死——[sectionTimes] 为空时表示该后端/该页面没有时间信息，
 * 调用方保留用户现有作息；非空时以它为准。
 */
data class JwFetchResult(
    val courses: List<CourseDraft>,
    val sectionTimes: List<SectionTimeDraft> = emptyList(),
)
