package com.zhijian.schedule.core.jw

import org.jsoup.Jsoup

/** 一学期成绩单里的一条课程成绩（强智 jsxsd `kscj/cjcx_list`） */
data class GradeRecord(
    val term: String,
    val code: String,
    val name: String,
    /** 原文成绩："85" / "A" / "优秀" */
    val score: String,
    /** 成绩标识（补考/重修标记，如 "*"） */
    val scoreFlag: String,
    val credit: Double,
    val hours: Int?,
    /** 教务给的学分绩点；等级制课程可能为空 */
    val gpa: Double?,
    /** 补重学期；非空表示该行是补考/重修记录 */
    val retakeTerm: String,
    val examMode: String,
    val examNature: String,
    val attr: String,
    val category: String,
)

/** 成绩单统计（可按学期过滤后再算） */
data class GradeStats(
    val courseCount: Int,
    val totalCredits: Double,
    /** 加权学分绩点；无可用绩点时 null */
    val weightedGpa: Double?,
    /** 加权平均分（仅百分制课程参与）；无百分制成绩时 null */
    val weightedAvg: Double?,
    val failCount: Int,
)

object GradeParser {
    private val FAIL_SCORES = setOf("不及格", "不合格", "未通过", "F", "缺考", "作弊")

    /** 解析 `table#dataList`；表头列名定位列索引，容忍列顺序变化 */
    fun parse(html: String): List<GradeRecord> {
        val doc = Jsoup.parse(html)
        val table = doc.getElementById("dataList") ?: return emptyList()
        val rows = table.select("tr")
        if (rows.size < 2) return emptyList()

        val headerRow = rows.firstOrNull() ?: return emptyList()
        val header = headerRow.select("th,td").map { it.text().trim() }

        fun col(label: String) = header.indexOfFirst { it == label }
        val iTerm = col("开课学期")
        val iCode = col("课程编号")
        val iName = col("课程名称")
        val iScore = col("成绩")
        val iFlag = col("成绩标识")
        val iCredit = col("学分")
        val iHours = col("总学时")
        val iGpa = col("绩点")
        val iRetake = col("补重学期")
        val iMode = col("考核方式")
        val iNature = col("考试性质")
        val iAttr = col("课程属性")
        val iCategory = col("课程性质")
        if (iTerm < 0 || iName < 0 || iScore < 0 || iCredit < 0) return emptyList()

        fun cell(
            cells: List<String>,
            idx: Int,
        ): String = if (idx in cells.indices) cells[idx] else ""

        return rows.drop(1).mapNotNull { tr ->
            val cells = tr.select("td").map { it.text().replace(Regex("\\s+"), " ").trim() }
            // 「未查询到数据」等说明行（单元格数不足）直接跳过
            if (cells.size < header.size) return@mapNotNull null
            val name = cell(cells, iName)
            if (name.isBlank()) return@mapNotNull null
            GradeRecord(
                term = cell(cells, iTerm),
                code = cell(cells, iCode),
                name = name,
                score = cell(cells, iScore),
                scoreFlag = cell(cells, iFlag),
                credit = cell(cells, iCredit).toDoubleOrNull() ?: 0.0,
                hours = cell(cells, iHours).toIntOrNull(),
                gpa = cell(cells, iGpa).toDoubleOrNull(),
                retakeTerm = cell(cells, iRetake),
                examMode = cell(cells, iMode),
                examNature = cell(cells, iNature),
                attr = cell(cells, iAttr),
                category = cell(cells, iCategory),
            )
        }
    }

    fun isFail(record: GradeRecord): Boolean = record.score in FAIL_SCORES || (record.gpa != null && record.gpa <= 0.0)

    /** 加权学分绩点 + 加权平均分 + 挂科数（全部按学分加权） */
    fun stats(records: List<GradeRecord>): GradeStats {
        var credits = 0.0
        var gpaWeighted = 0.0
        var gpaCredits = 0.0
        var scoreWeighted = 0.0
        var scoreCredits = 0.0
        var fails = 0
        records.forEach { r ->
            credits += r.credit
            if (r.gpa != null) {
                gpaWeighted += r.credit * r.gpa
                gpaCredits += r.credit
            }
            val score = r.score.toDoubleOrNull()
            if (score != null) {
                scoreWeighted += r.credit * score
                scoreCredits += r.credit
            }
            if (isFail(r)) fails++
        }
        return GradeStats(
            courseCount = records.size,
            totalCredits = credits,
            weightedGpa = if (gpaCredits > 0) gpaWeighted / gpaCredits else null,
            weightedAvg = if (scoreCredits > 0) scoreWeighted / scoreCredits else null,
            failCount = fails,
        )
    }
}
