package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 教务课表适配器。首个目标：东莞职院 强智 jsxsd（xskb_list.do）。
 * 兼容两代页面结构：
 * - 新版（强智）：行首节次列为 th「第一大节 08:20-09:55」，课程在 td 内 div.kbcontent；
 *   周次行形如 "12(周)[01-02-03-04节]"（方括号内为权威节次区间）；
 *   div.kbcontent1 是隐藏的重复渲染（无老师），不参与解析。
 * - 旧版/正方：行首为「1-2节」td，课程直接铺在单元格文本里，多课以分隔线或相邻 div 分隔。
 */
object ZhengFangAdapter : JwAdapter {

    override val id = "zhengfang"
    override val name = "正方教务"

    override fun supports(url: String): Boolean = true

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    override suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): List<CourseDraft> = withContext(Dispatchers.IO) {
        try {
            fetchCourses(client, baseUrl, totalWeeks)
        } catch (e: JwException.NeedsLogin) {
            // 会话过期：先尝试统一认证静默重登（后台自动同步与手动抓取共用），再重试一次
            if (!JwSessionRecovery.trySilentRecover(client, baseUrl)) throw e
            fetchCourses(client, baseUrl, totalWeeks)
        }
    }

    private suspend fun fetchCourses(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): List<CourseDraft> = withContext(Dispatchers.IO) {
        val base = normalizeBase(baseUrl)
        val referer = "${base}framework/xsMain.jsp"
        val candidates = listOf(
            "${base}xskb/xskb_list.do",
            "${base}xskb/xskbList.do",
            "${base}kbcx/xskbcx_cxXsKb.html",
        )
        var lastError: String? = null
        for (url in candidates) {
            val html = try {
                get(client, url, referer)
            } catch (e: JwException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                continue
            }
            if (isLoginPage(html)) throw JwException.NeedsLogin()
            val drafts = parse(html, totalWeeks)
            if (drafts.isNotEmpty()) return@withContext drafts
            lastError = "未在页面中找到课表数据"
        }
        throw JwException.ParseFailed("抓取失败：${lastError ?: "页面结构不匹配"}")
    }

    private fun isLoginPage(html: String): Boolean =
        isSessionDeadPage(html) ||
            (html.contains("<title>登录</title>") && !html.contains("kbtable"))

    private fun get(client: OkHttpClient, url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw JwException.NetworkFailed("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /**
     * 归一化：保留 scheme://host[/jsxsd/]，去掉多余路径与参数；
     * http 统一升级为 https（实测该校 http 入口 404）；
     * 非 jsxsd 链接（如统一认证登录入口 authserver…sso.jsp）回落到教务基地址——
     * 校外流程里「登录入口」与「抓取地址」往往不是同一个 URL。
     */
    fun normalizeBase(url: String): String {
        var raw = url.trim()
        if (raw.isEmpty()) throw JwException.NetworkFailed("教务地址为空")
        if (!raw.startsWith("http", ignoreCase = true)) raw = "https://$raw"
        if (raw.startsWith("http://", ignoreCase = true)) raw = "https://" + raw.substring(7)
        val parsed = raw.toHttpUrlOrNull() ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val idx = path.indexOf("/jsxsd/")
        if (idx < 0) return DEFAULT_BASE
        val basePath = path.substring(0, idx + "/jsxsd/".length)
        return parsed.newBuilder().encodedPath(basePath).query(null).fragment(null).build().toString()
    }

    private const val DEFAULT_BASE = "https://jw.dgpt.edu.cn/jsxsd/"

    // ---------- 解析 ----------

    fun parse(html: String, totalWeeks: Int): List<CourseDraft> {
        val doc = Jsoup.parse(html)
        val table = doc.getElementById("kbtable")
            ?: doc.selectFirst("table#kbtable")
            ?: doc.selectFirst("table.kbtable")
            ?: return emptyList()
        val rows = table.select("tr").filter { it.select("th,td").isNotEmpty() }
        if (rows.isEmpty()) return emptyList()

        // 表头星期映射（列索引 → 星期）；强智表头在 th，旧版在 td
        var headerDays: Map<Int, Int> = emptyMap()
        var headerRowIndex = -1
        for (i in 0 until minOf(3, rows.size)) {
            val cells = rows[i].select("th,td")
            val mapping = LinkedHashMap<Int, Int>()
            cells.forEachIndexed { idx, c -> parseDay(c.text())?.let { mapping[idx] = it } }
            if (mapping.size >= 3) {
                headerDays = mapping
                headerRowIndex = i
                break
            }
        }
        val dayValues = headerDays.entries.sortedBy { it.key }.map { it.value }

        val dataRows = rows.filterIndexed { i, _ -> i != headerRowIndex }
        val bigRowLayout = dataRows.size <= 7
        val drafts = mutableListOf<CourseDraft>()
        dataRows.forEachIndexed { ordinal, tr ->
            val cells = tr.select("th,td")
            if (cells.isEmpty()) return@forEachIndexed
            // 备注等单元格不足的说明行跳过
            if (headerDays.size >= 3 && cells.size < headerDays.size) return@forEachIndexed
            val rowRange = parseSectionLabel(cells[0].text())
            val courseCells = if (headerDays.size >= 3) cells.takeLast(headerDays.size) else cells
            courseCells.forEachIndexed { colIdx, td ->
                val day = when {
                    headerDays.size >= 3 -> dayValues.getOrNull(colIdx)
                    colIdx == 0 -> null
                    else -> colIdx.takeIf { it in 1..7 }
                } ?: return@forEachIndexed
                drafts += parseCell(td, day, rowRange, ordinal, bigRowLayout, totalWeeks)
            }
        }
        return drafts.distinct()
    }

    /** 单元格 → 课程；新版只认可见的 div.kbcontent（kbcontent1 为隐藏重复），旧版整格按分隔线拆 */
    private fun parseCell(
        td: Element,
        day: Int,
        rowRange: IntRange?,
        ordinal: Int,
        bigRowLayout: Boolean,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val blocks = td.select("div.kbcontent")
        val sources = if (blocks.isNotEmpty()) blocks.map { it.html() } else listOf(td.html())
        val drafts = mutableListOf<CourseDraft>()
        sources.forEach { raw ->
            raw.split(Regex("(?i)<hr[^>]*/?>|-{6,}|</div>")).forEach { fragment ->
                val lines = fragmentLines(fragment)
                mapLines(lines, day, rowRange, ordinal, bigRowLayout, totalWeeks)?.let { drafts += it }
            }
        }
        return drafts
    }

    private fun fragmentLines(fragment: String): List<String> =
        fragment
            .split(Regex("(?i)<br\\s*/?>"))
            .map { Jsoup.parse(it).text().replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }

    private fun mapLines(
        lines: List<String>,
        day: Int,
        rowRange: IntRange?,
        ordinal: Int,
        bigRowLayout: Boolean,
        totalWeeks: Int,
    ): CourseDraft? {
        if (lines.isEmpty()) return null
        val name = lines.first().trim().trimEnd('*', '（', '(')
        if (name.isEmpty() || name.length > 30) return null

        val weekLine = lines.firstOrNull {
            it.contains("周") && !it.contains("星期") && (it.any { c -> c.isDigit() } || it.contains("全"))
        } ?: lines.firstOrNull { Regex("""^[\d,\-()（）单双]+$""").matches(it) && it.any { c -> c.isDigit() } }

        val rest = lines.drop(1).filter { it != weekLine }
        val teacher = rest.firstOrNull()?.takeIf { it.length <= 20 }.orEmpty()
        val room = rest.getOrNull(1)?.takeIf { it.length <= 30 }.orEmpty()

        val weekExpr = weekLine?.substringBefore('[')
        val mask = WeekMask.parseExpression(totalWeeks, weekExpr) ?: WeekMask.of(totalWeeks)
        val range = bracketSections(weekLine)
            ?: rowRange
            ?: rowFallbackRange(ordinal, bigRowLayout)
        return CourseDraft(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startSection = range.first.coerceIn(1, 12),
            endSection = range.last.coerceIn(1, 12),
            weekMask = mask,
        )
    }

    /** 强智周次行内 [01-02-03-04节] 括号 = 权威节次区间 */
    private fun bracketSections(weekLine: String?): IntRange? {
        val m = Regex("""\[([\d\-\s]+)节]""").find(weekLine ?: return null) ?: return null
        val nums = Regex("""\d+""").findAll(m.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.isEmpty()) return null
        return nums.min()..nums.max()
    }

    private fun rowFallbackRange(ordinal: Int, bigRowLayout: Boolean): IntRange =
        if (bigRowLayout) {
            val period = (ordinal + 1).coerceIn(1, 6)
            (period * 2 - 1)..(period * 2)
        } else {
            val section = (ordinal + 1).coerceIn(1, 12)
            section..section
        }

    /** 行首节次标签："1-2节" / "3,4节" / "第一大节"；纯时间 "08:00-09:40" 返回 null */
    private fun parseSectionLabel(text: String): IntRange? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (Regex("""^\d{1,2}\s*[:：]\s*\d{2}""").containsMatchIn(t)) return null
        val big = Regex("""第([一二三四五六七八九十\d]+)大节""").find(t)
        if (big != null) {
            val n = cnNum(big.groupValues[1]) ?: return null
            return (n * 2 - 1)..(n * 2)
        }
        val nums = Regex("""\d+""").findAll(t).mapNotNull { it.value.toIntOrNull() }.toList()
        return when {
            nums.size == 1 -> nums[0]..nums[0]
            nums.size >= 2 -> minOf(nums[0], nums[1])..maxOf(nums[0], nums[1])
            else -> null
        }
    }

    private fun cnNum(s: String): Int? = when (s) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "七" -> 7
        "八" -> 8
        "九" -> 9
        "十" -> 10
        else -> s.toIntOrNull()
    }

    private fun parseDay(text: String): Int? {
        val t = text.trim()
        if (t.length > 8) return null
        if (t.contains("星期") || t.contains("周")) {
            val zh = listOf("一", "二", "三", "四", "五", "六", "日", "天")
            zh.forEachIndexed { i, c -> if (t.contains(c)) return (i + 1).coerceAtMost(7) }
        }
        return null
    }
}
