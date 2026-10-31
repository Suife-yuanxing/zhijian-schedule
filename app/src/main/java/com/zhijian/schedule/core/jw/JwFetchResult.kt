package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft

/**
 * 一次教务抓取的完整结果：课程 + 页面上写的上课时间 + 课表元数据。
 *
 * 作息时间不再由代码写死——[sectionTimes] 为空时表示该后端/该页面没有时间信息，
 * 调用方保留用户现有作息；非空时以它为准。
 *
 * [meta] 是教务自己声明的学期元数据（学期名 / 第 1 周周一 / 总周数）；为 null 表示
 * 该来源没给，本地值原样保留。给了就随导入同步——周次映射从此跟学校校历一致，
 * 不再靠用户手填。
 */
data class JwFetchResult(
    val courses: List<CourseDraft>,
    val sectionTimes: List<SectionTimeDraft> = emptyList(),
    val meta: JwMeta? = null,
)

/**
 * 教务声明的学期元数据。字段可各自缺省：学校没配的部分不覆盖本地值。
 *
 * @param semester 学期名（如「2026-2027学年第一学期」）；空串表示不覆盖
 * @param startDate 第 1 周周一，ISO `yyyy-MM-dd`；空串表示不覆盖
 * @param totalWeeks 学期总周数；非正数表示不覆盖
 */
data class JwMeta(
    val semester: String = "",
    val startDate: String = "",
    val totalWeeks: Int = 0,
)
