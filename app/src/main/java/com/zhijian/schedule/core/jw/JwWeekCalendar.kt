package com.zhijian.schedule.core.jw

import org.jsoup.Jsoup
import java.time.LocalDate

/**
 * 强智 jsxsd「教学周历」页 → 学期元数据（第 1 周周一 + 总周数）。
 *
 * ## 这条解析在修什么
 * 课表页（`xskb_list.do`）只给「第 N 周上什么课」，**不给第 1 周是哪一天**。
 * 于是周次映射只能靠本地那个 `startDate`，而它来自首启建表时的
 * `LocalDate.now().with(DayOfWeek.MONDAY)`——今天建表就是今天这周一。
 *
 * 结果：学期第 10 周装进去，`weekOfSemester` 算出第 1 周，顶栏永远写「第 1 周」。
 * 算法本身没错，起点错了。周历页正好写着这个起点，抓课表时顺带取回来。
 *
 * ## 页面结构（2026-11-02 东莞职院实抓）
 * ```html
 * <table id="kbtable">
 *   <tr><th>…</th><th>星期一</th>…<th>星期日</th><th>备注</th></tr>
 *   <tr height='28'><td>1</td><td title='2026年08月31'>31</td>…<td title='2026年09月06'>09月06日</td>…</tr>
 *   <tr height='28'><td>2</td><td title='2026年09月07'>07</td>…</tr>
 * ```
 * 首列是周次，日期藏在单元格的 `title` 里（正文只有「31」「09月06日」这种，
 * 缺年月，不能拿来当日期用）。
 *
 * 纯逻辑、不碰网络，便于 JVM 单测（见 `JwWeekCalendarTest`）。
 */
object JwWeekCalendar {
    /** 周历页相对教务根路径的地址（与课表页同源同会话） */
    const val PATH = "jxzl/jxzl_query"

    /**
     * 抓一次周历页并解析；拿不到就返回 null，绝不能因此让抓课表失败。
     *
     * 会话过期（[JwException.NeedsLogin]）照旧向上抛：那是整条链路的状态，
     * 不是「周历页没开放」，吞掉它会让用户在过期会话上反复重试课表页。
     */
    fun fetch(
        base: String,
        get: (String) -> String,
    ): JwMeta? =
        try {
            parse(get(base + PATH))
        } catch (e: JwException.NeedsLogin) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** 单元格 `title='2026年08月31'` */
    private val CELL_DATE = Regex("""^(\d{4})年(\d{1,2})月(\d{1,2})$""")

    /** 学期下拉的选中项，如 `2026-2027-1`；命名分组让取值处不用数下标 */
    private val SEMESTER_ID = Regex("""^(?<start>\d{4})-(?<end>\d{4})-(?<term>\d{1,2})$""")

    /**
     * 周历表 → (总周数, 第 1 周周一 ISO)。
     *
     * 认不出就返回 null（调用方保留本地值）：宁可不改，也不能把错日期写进去——
     * 写错的开学日期会让整学期的周次映射全偏。
     */
    fun parse(html: String): JwMeta? {
        val weeks = weekRows(html)
        val totalWeeks = weeks.maxOfOrNull { it.first } ?: return null
        // 第 1 周那一行里最靠左的日期就是周一；行内单元格顺序即周一到周日
        val startDate = weeks.minByOrNull { it.first }?.second
        val usable = startDate != null && totalWeeks in 1..MAX_WEEKS
        if (!usable) return null
        return JwMeta(
            semester = semesterName(html),
            startDate = startDate.toString(),
            totalWeeks = totalWeeks,
        )
    }

    /** 每行的 (周次, 该行第一个日期)；行里没有合法日期时整行丢弃 */
    private fun weekRows(html: String): List<Pair<Int, LocalDate>> {
        val table =
            Jsoup
                .parse(html)
                .selectFirst("table#kbtable")
                ?: Jsoup.parse(html).selectFirst("table")
                ?: return emptyList()
        return table.select("tr").mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == TD || it.tagName() == TH }
            val label = cells.firstOrNull()?.text()?.trim()
            val weekNo = label?.toIntOrNull() ?: return@mapNotNull null
            val firstDate =
                cells.drop(1).firstNotNullOfOrNull { cell ->
                    CELL_DATE
                        .find(cell.attr("title").trim())
                        ?.let { dateOf(it) }
                } ?: return@mapNotNull null
            weekNo to firstDate
        }
    }

    private fun dateOf(m: MatchResult): LocalDate? {
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /**
     * 学期下拉的选中项 → 学期名；认不出返回空串（不覆盖本地值）。
     *
     * jsxsd 的学期码有两种写法：`-1/-2` 与 `-3/-12`（3 = 第一学期，12 = 第二学期），
     * 两种都要认，否则换一所学校学期名就同步不过来。
     */
    fun semesterName(html: String): String {
        val selected =
            if (html.isBlank()) {
                null
            } else {
                Jsoup
                    .parse(html)
                    .let { doc ->
                        doc.selectFirst("select#xnxq01id option[selected]")
                            ?: doc.selectFirst("select[name=xnxq01id] option[selected]")
                    }?.attr("value")
                    ?.trim()
            }
        val parts = selected?.let { SEMESTER_ID.find(it) }?.groups
        val start = parts?.get("start")?.value.orEmpty()
        val end = parts?.get("end")?.value.orEmpty()
        // 学期码 → 第一/第二学期的映射与正方共用 [JwSemesterLabel]，各写一份必然漂移
        return JwSemesterLabel.of(start + "-" + end, parts?.get("term")?.value.orEmpty())
    }

    /** 与 `TimetableMetaPlan.MAX_WEEKS` 同口径 */
    private const val MAX_WEEKS = 30
    private const val TD = "td"
    private const val TH = "th"
}
