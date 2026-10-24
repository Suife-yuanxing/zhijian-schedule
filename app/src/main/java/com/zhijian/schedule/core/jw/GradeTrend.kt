package com.zhijian.schedule.core.jw

/**
 * 学期维度的绩点统计（与 GradeParser.stats 的全量口径分开）：
 * 只计「有绩点且非补考重修」的课程——等级制课程与补考记录不参与 GPA 与学分。
 */
object GradeTrend {

    data class TermSummary(
        val term: String,
        /** 加权绩点；该学期无可用绩点时 null */
        val gpa: Double?,
        /** 参与统计的学分合计 */
        val credits: Double,
        val courseCount: Int,
    )

    fun perTerm(records: List<GradeRecord>): List<TermSummary> =
        records.asSequence()
            .filter { it.gpa != null && it.retakeTerm.isBlank() }
            .groupBy { it.term }
            .map { (term, rows) ->
                TermSummary(
                    term = term,
                    gpa = weighted(rows),
                    credits = rows.sumOf { it.credit },
                    courseCount = rows.size,
                )
            }
            .sortedByDescending { it.term }

    /** 最新学期（学期名字典序最大）；无数据时 null */
    fun latest(records: List<GradeRecord>): TermSummary? = perTerm(records).firstOrNull()

    private fun weighted(rows: List<GradeRecord>): Double? {
        var weighted = 0.0
        var credits = 0.0
        rows.forEach { r ->
            weighted += r.credit * (r.gpa ?: 0.0)
            credits += r.credit
        }
        return if (credits > 0) weighted / credits else null
    }
}
