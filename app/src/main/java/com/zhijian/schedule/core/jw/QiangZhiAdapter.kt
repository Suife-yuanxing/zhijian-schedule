package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 强智科技 jsxsd 教务适配器（`/jsxsd/`、`xskb_list.do`、`#kbtable`、`div.kbcontent`）。
 *
 * 说明：本类原名 ZhengFangAdapter，但实现与正方无关；2026-10-19 调研确认
 * jsxsd 是强智科技的产品线，正方新版是 `/jwglxt/` + `kbList` JSON（见 [ZhengFangNewAdapter]）。
 *
 * 兼容两代页面结构：
 * - 新版（强智）：行首节次列为 th「第一大节 08:20-09:55」，课程在 td 内 div.kbcontent；
 *   周次行形如 "12(周)[01-02-03-04节]"（方括号内为权威节次区间）；
 *   div.kbcontent1 是隐藏的重复渲染（无老师），不参与解析。
 * - 旧版：行首为「1-2节」td，课程直接铺在单元格文本里，多课以分隔线或相邻 div 分隔。
 */
object QiangZhiAdapter : JwAdapter {
    override val id = "qiangzhi"
    override val name = "强智 jsxsd"

    override val steps =
        AdapterSteps(
            timetablePage = "学生课表",
            note = "左侧菜单里没有「学生课表」时，请让教务开放该菜单权限",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("/jsxsd/") || path == "/jsxsd" -> 100
            path.contains("xskb_list.do") -> 90
            path.contains("logon.do") || path.contains("logintoxk") -> 90
            path.contains("/jxd/") -> 70
            // 目录条目必须与本校协议一致：否则收录了 URP/树维 的学校会被误判成强智
            SchoolCatalog.byHost(parsed.host)?.adapterId == id -> JwMatchScore.CATALOG_WEAK
            else -> 0
        }
    }

    override fun ssoEntry(baseUrl: String): String? = runCatching { "${normalizeBase(baseUrl)}sso.jsp" }.getOrNull()

    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            try {
                fetchCourses(client, baseUrl, totalWeeks)
            } catch (e: JwException.NeedsLogin) {
                // 会话过期：先尝试统一认证静默重登（后台自动同步与手动抓取共用），再重试一次
                if (!JwSessionRecovery.trySilentRecover(client, this@QiangZhiAdapter, baseUrl)) throw e
                fetchCourses(client, baseUrl, totalWeeks)
            }
        }

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：`kscj/cjcx_list?kksj=`（kksj 留空 = 全学期），与课表共用
     * WebView 会话；会话过期同样先走统一认证静默重登。URL 来自用户输入，先过
     * [JwUrlGuard]。
     */
    override suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> {
        val base = JwUrlGuard.safeHttpUrl(baseUrl)?.let { normalizeBase(it.toString()) } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                fetchGradeRecords(client, base)
            } catch (e: JwException.NeedsLogin) {
                if (!JwSessionRecovery.trySilentRecover(client, this@QiangZhiAdapter, baseUrl)) throw e
                fetchGradeRecords(client, base)
            }
        }
    }

    private suspend fun fetchGradeRecords(
        client: OkHttpClient,
        base: String,
    ): List<GradeRecord> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("${base}kscj/cjcx_list?kksj=")
                    .header("User-Agent", UA)
                    .header("Referer", "${base}kscj/cjcx_frm")
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw JwException.NetworkFailed("HTTP ${response.code}")
                val html = response.body?.string().orEmpty()
                if (isLoginPage(html)) throw JwException.NeedsLogin()
                GradeParser.parse(html)
            }
        }

    private suspend fun fetchCourses(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            val referer = "${base}framework/xsMain.jsp"
            val candidates =
                listOf(
                    "${base}xskb/xskb_list.do",
                    "${base}xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL",
                )
            var lastError: String? = null
            for (url in candidates) {
                val html =
                    try {
                        get(client, url, referer)
                    } catch (e: JwException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message
                        continue
                    }
                if (isLoginPage(html)) throw JwException.NeedsLogin()
                val drafts = parse(html, totalWeeks)
                if (drafts.isNotEmpty()) {
                    return@withContext JwFetchResult(drafts, SectionTimes.fromHtml(html))
                }
                lastError = "未在页面中找到课表数据"
            }
            throw JwException.ParseFailed("抓取失败：${lastError ?: "页面结构不匹配"}")
        }

    private fun isLoginPage(html: String): Boolean =
        isSessionDeadPage(html) ||
            (html.contains("<title>登录</title>") && !html.contains("kbtable"))

    private fun get(
        client: OkHttpClient,
        url: String,
        referer: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }

    /**
     * 归一化：保留 scheme://host[/jsxsd/]（含 WebVPN 的 /https/xxx/jsxsd/ 前缀），
     * 去掉多余路径与参数。不再把 http 强制升级 https——大量高职院校只有 http 入口。
     *
     * 非 jsxsd 链接只在「已实证学校档案」里补全真实子路径；
     * 未知学校直接报错，绝不回落到东莞职院（旧实现的安全隐患）。
     */
    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val idx = path.indexOf("/jsxsd/")
        val basePath =
            when {
                idx >= 0 -> path.substring(0, idx + "/jsxsd/".length)
                path == "/jsxsd" -> "/jsxsd/"
                else ->
                    SchoolCatalog.knownBasePath(parsed.host)
                        ?: throw JwException.NetworkFailed("请在教务地址中包含 /jsxsd/ 路径")
            }
        return parsed
            .newBuilder()
            .encodedPath(basePath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun parseUrl(raw: String): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.startsWith("http", ignoreCase = true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        return withScheme.toHttpUrlOrNull()
    }

    // ---------- 解析 ----------

    fun parse(
        html: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val doc = Jsoup.parse(html)
        val table =
            doc.getElementById("kbtable")
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
                val day =
                    when {
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
            .map {
                Jsoup
                    .parse(it)
                    .text()
                    .replace('\u00A0', ' ')
                    .trim()
            }.filter { it.isNotEmpty() }

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

        val weekLine =
            lines.firstOrNull {
                it.contains("周") && !it.contains("星期") && (it.any { c -> c.isDigit() } || it.contains("全"))
            } ?: lines.firstOrNull { Regex("""^[\d,\-()（）单双]+$""").matches(it) && it.any { c -> c.isDigit() } }

        val rest = lines.drop(1).filter { it != weekLine }
        val teacher = rest.firstOrNull()?.takeIf { it.length <= 20 }.orEmpty()
        val room = rest.getOrNull(1)?.takeIf { it.length <= 30 }.orEmpty()

        val weekExpr = weekLine?.substringBefore('[')
        val mask = WeekMask.parseExpression(totalWeeks, weekExpr) ?: WeekMask.of(totalWeeks)
        val range =
            bracketSections(weekLine)
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

    private fun rowFallbackRange(
        ordinal: Int,
        bigRowLayout: Boolean,
    ): IntRange =
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

    private fun cnNum(s: String): Int? =
        when (s) {
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
