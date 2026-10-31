package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.Charset

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

    /**
     * 成绩端点候选轮询（青果 / URP / 树维等 HTML 成绩页共用）：
     * 逐个 GET，会话死页抛 NeedsLogin，页面交给 [parseGeneric] 通用表头解析；
     * 第一个解析出记录的候选即返回，全不中返回空（调用方按未抓到静默跳过）。
     * URL 已由调用方过 [JwUrlGuard]。老 ASP 部署（正方旧版）页面是 GBK，
     * 传 [charset] 指定解码；响应头没写 charset 时 OkHttp 默认按 UTF-8 解，
     * 中文表头会变乱码。
     */
    suspend fun fetchViaCandidates(
        client: OkHttpClient,
        candidates: List<String>,
        referer: String,
        userAgent: String,
        isDeadPage: (String) -> Boolean,
        charset: Charset = Charsets.UTF_8,
    ): List<GradeRecord> =
        withContext(Dispatchers.IO) {
            for (url in candidates) {
                val html =
                    try {
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", userAgent)
                            .header("Referer", referer)
                            .get()
                            .build()
                            .let { client.newCall(it).execute() }
                            .use { response ->
                                if (!response.isSuccessful) {
                                    ""
                                } else {
                                    val bytes = response.body?.bytes() ?: ByteArray(0)
                                    String(bytes, charset)
                                }
                            }
                    } catch (ignored: IOException) {
                        ""
                    }
                if (html.isBlank()) continue
                if (isDeadPage(html)) throw JwException.NeedsLogin()
                val records = parseGeneric(html)
                if (records.isNotEmpty()) return@withContext records
            }
            emptyList()
        }

    /**
     * 通用成绩表解析（青果 / URP / 树维等 HTML 成绩页）。
     *
     * 不锁 table id：全页找「表头同时含课程名与成绩列」的表，列位按别名定位、
     * 容忍列序与措辞差异（口径与 [parse] 相同）。找不到这样的表返回空列表，
     * 由调用方决定是否继续尝试别的端点。
     */
    fun parseGeneric(html: String): List<GradeRecord> {
        val doc = Jsoup.parse(html)
        val tables = doc.getElementsByTag("table")
        for (table in tables) {
            val rows = table.select("tr")
            val columns = gradeColumns(rows)
            if (columns != null) {
                val records = rows.drop(1).mapNotNull { parseGenericRow(it, columns) }
                if (records.isNotEmpty()) return records
            }
        }
        return emptyList()
    }

    /** 表头 → 列位映射；表无两行以上、或缺「课程名 + 成绩」两列则 null（换下一个表） */
    private fun gradeColumns(rows: List<Element>): GradeColumns? {
        val headerRow = rows.firstOrNull() ?: return null
        val header = headerRow.select("th,td").map { it.text().trim() }
        if (rows.size < 2 || header.size < 2) return null

        fun col(vararg aliases: String) = header.indexOfFirst { it in aliases }
        val iTerm = col("开课学期", "学年", "学期", "学年学期")
        val iCode = col("课程编号", "课程代码", "课程号")
        val iName = col("课程名称", "课程")
        val iScore = col("成绩", "总评成绩", "最终成绩", "总分")
        // 最少要有课程名 + 成绩两列才算成绩表；学分缺失照样收（成绩列表仍可用）
        if (iName < 0 || iScore < 0) return null
        return GradeColumns(
            term = iTerm,
            code = iCode,
            name = iName,
            score = iScore,
            flag = col("成绩标识", "标记"),
            credit = col("学分"),
            hours = col("总学时", "学时"),
            gpa = col("绩点"),
            retake = col("补重学期", "重修学期"),
            mode = col("考核方式", "考试方式"),
            nature = col("考试性质", "考试类别"),
            attr = col("课程属性", "课程性质"),
            category = col("课程性质"),
            headerSize = header.size,
        )
    }

    private fun parseGenericRow(
        tr: Element,
        columns: GradeColumns,
    ): GradeRecord? {
        val cells = tr.select("td").map { it.text().replace(Regex("\\s+"), " ").trim() }
        if (cells.size < columns.headerSize) return null
        val name = columns.cell(cells, columns.name)
        if (name.isBlank()) return null
        return GradeRecord(
            term = columns.cell(cells, columns.term),
            code = columns.cell(cells, columns.code),
            name = name,
            score = columns.cell(cells, columns.score),
            scoreFlag = columns.cell(cells, columns.flag),
            credit = columns.cell(cells, columns.credit).toDoubleOrNull() ?: 0.0,
            hours = columns.cell(cells, columns.hours).toIntOrNull(),
            gpa = columns.cell(cells, columns.gpa).toDoubleOrNull(),
            retakeTerm = columns.cell(cells, columns.retake),
            examMode = columns.cell(cells, columns.mode),
            examNature = columns.cell(cells, columns.nature),
            attr = columns.cell(cells, columns.attr),
            category = columns.cell(cells, columns.category),
        )
    }

    /** 通用成绩表的列位（表头别名定位后的索引）；负数表示该列不存在 */
    private data class GradeColumns(
        val term: Int,
        val code: Int,
        val name: Int,
        val score: Int,
        val flag: Int,
        val credit: Int,
        val hours: Int,
        val gpa: Int,
        val retake: Int,
        val mode: Int,
        val nature: Int,
        val attr: Int,
        val category: Int,
        val headerSize: Int,
    ) {
        fun cell(
            cells: List<String>,
            idx: Int,
        ): String = if (idx in cells.indices) cells[idx] else ""
    }

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
