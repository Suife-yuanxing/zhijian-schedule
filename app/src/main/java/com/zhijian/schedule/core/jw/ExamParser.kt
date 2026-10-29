package com.zhijian.schedule.core.jw

import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneId

/** 一条考试安排（强智 jsxsd 考试安排页） */
data class ExamRecord(
    val name: String,
    val room: String,
    val seat: String,
    /** 开考时刻（本地时区） */
    val examTime: LocalDateTime,
    val note: String,
)

object ExamParser {
    /** "2026-11-02 14:00~15:50" 取起点；兼容 / 与 . 分隔 */
    private val TIME = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})""")

    /** 列头模糊定位，容忍列序与措辞差异（同 GradeParser 思路） */
    fun parse(html: String): List<ExamRecord> {
        if (isSessionDeadPage(html)) return emptyList()
        val doc = Jsoup.parse(html)
        val table = doc.getElementById("dataList") ?: doc.selectFirst("table") ?: return emptyList()
        val rows = table.select("tr")
        if (rows.size < 2) return emptyList()
        val header = rows.firstOrNull()?.select("th,td")?.map { it.text().trim() } ?: return emptyList()

        fun col(vararg labels: String) = header.indexOfFirst { h -> labels.any { h.contains(it) } }
        val iName = col("课程名称", "课程")
        val iTime = col("考试时间", "时间", "日期")
        val iRoom = col("考场", "考试地点", "地点")
        val iSeat = col("座位号", "座位")
        val iNote = col("备注")
        if (iName < 0 || iTime < 0) return emptyList()

        fun cell(
            cells: List<String>,
            idx: Int,
        ) = if (idx in cells.indices) cells[idx] else ""
        return rows
            .drop(1)
            .mapNotNull { tr ->
                val cells = tr.select("td").map { it.text().replace(Regex("\\s+"), " ").trim() }
                if (cells.size <= maxOf(iName, iTime)) return@mapNotNull null
                val name = cell(cells, iName)
                val m = TIME.find(cell(cells, iTime)) ?: return@mapNotNull null
                ExamRecord(
                    name = name,
                    room = cell(cells, iRoom),
                    seat = cell(cells, iSeat),
                    examTime =
                        LocalDateTime.of(
                            m.groupValues[1].toInt(),
                            m.groupValues[2].toInt(),
                            m.groupValues[3].toInt(),
                            m.groupValues[4].toInt(),
                            m.groupValues[5].toInt(),
                        ),
                    note = cell(cells, iNote),
                )
            }.filter { it.name.isNotBlank() }
            .distinctBy { it.name to it.examTime }
    }

    fun epochMillis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
