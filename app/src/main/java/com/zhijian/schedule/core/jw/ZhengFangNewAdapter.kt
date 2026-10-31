package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate

/**
 * 正方软件新版教务（jwglxt）适配器。
 *
 * 登录由 WebView 完成；本适配器只带共享 Cookie 重放课表查询：
 * `POST /jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`
 * 表单 `xnm`（学年，如 2025-2026）/ `xqm`（3 = 第一学期，12 = 第二学期），
 * 返回 JSON 的 `kbList[]`（kcmc/xm/xqj/jc/zcd/cdmc）。
 *
 * 旧版 ASP.NET 正方（default2.aspx / #table1）不是本类；需要单独的表格适配器。
 */
object ZhengFangNewAdapter : JwAdapter {
    override val id = "zhengfang_new"
    override val name = "正方新版（jwglxt）"

    override val steps =
        AdapterSteps(
            timetablePage = "信息查询 → 学生课表",
            note = "先在页面顶部把学年学期选成当前学期，再点抓取",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val INDEX_PATH = "kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"

    /** 学生课表 / 个人课表两个常见入口；不同学校部署只开其中一个 */
    private val TIMETABLE_PATHS =
        listOf(
            "kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151",
            "kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508",
        )

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        return when {
            path.startsWith("/jwglxt/") || path == "/jwglxt" -> 100
            path.contains("login_slogin.html") -> 95
            path.contains("xskbcx_cx") -> 90
            // 目录条目兜底：用户只填了域名时也能按本校协议识别
            SchoolCatalog.byHost(parsed.host)?.adapterId == id -> JwMatchScore.CATALOG
            else -> 0
        }
    }

    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            val referer = "${base}xtgl/index_initMenu.html"

            // 先读课表页里「当前选中」的学年/学期；读不到再按日期推断。
            val indexHtml = runCatching { get(client, "${base}$INDEX_PATH", referer) }.getOrNull().orEmpty()
            if (indexHtml.isNotEmpty() && isSessionDeadPage(indexHtml)) throw JwException.NeedsLogin()

            val selected = parseSelectedTerm(indexHtml)
            val candidates = (listOfNotNull(selected) + termCandidates()).distinct()
            var lastError: String? = null
            for ((xnm, xqm) in candidates) {
                for (path in TIMETABLE_PATHS) {
                    val body = post(client, "${base}$path", "${base}$INDEX_PATH", xnm, xqm)
                    if (isSessionDeadPage(body)) throw JwException.NeedsLogin()
                    val drafts = parseJson(body, totalWeeks)
                    if (drafts.isNotEmpty()) {
                        // kbList 只有节次序号，没有时分；只在课表页 HTML 里出现时间表时才带上
                        return@withContext JwFetchResult(drafts, SectionTimes.fromHtml(indexHtml))
                    }
                    lastError = "学期 $xnm/$xqm 未返回课程"
                }
            }
            throw JwException.ParseFailed(
                "正方新版未返回课表：${lastError ?: "可能学期参数不匹配或页面结构已改"}",
            )
        }

    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val idx = path.indexOf("/jwglxt/")
        val basePath =
            when {
                idx >= 0 -> path.substring(0, idx + "/jwglxt/".length)
                path == "/jwglxt" -> "/jwglxt/"
                else -> throw JwException.NetworkFailed("请在正方教务地址中包含 /jwglxt/ 路径")
            }
        return parsed
            .newBuilder()
            .encodedPath(basePath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：`POST /jwglxt/kbcj/xs_kccj_query.do?gnmkdm=N305005`。
     * 先试空 xnm/xqm（多数部署返回全学年），被拒或为空再退到课表页当前选中的
     * 学年/学期。响应形状各版本有差（平铺 / `XSKC` 嵌套），解析按别名容错取值。
     */
    override suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> {
        val base = JwUrlGuard.safeHttpUrl(baseUrl)?.let { normalizeBase(it.toString()) } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val referer = "${base}xtgl/index_initMenu.html"
            val indexHtml = runCatching { get(client, "${base}$INDEX_PATH", referer) }.getOrNull().orEmpty()
            if (indexHtml.isNotEmpty() && isSessionDeadPage(indexHtml)) throw JwException.NeedsLogin()

            val selected = parseSelectedTerm(indexHtml)
            val attempts =
                (listOfNotNull(selected) + listOf("" to "")).distinct()
            for ((xnm, xqm) in attempts) {
                val body =
                    post(
                        client,
                        "${base}kbcj/xs_kccj_query.do?gnmkdm=N305005",
                        referer,
                        xnm,
                        xqm,
                    )
                if (isSessionDeadPage(body)) throw JwException.NeedsLogin()
                val records = parseZhengFangGrades(body)
                if (records.isNotEmpty()) return@withContext records
            }
            emptyList()
        }
    }

    /**
     * 成绩 JSON → 记录。各版本字段名不一（`items[].XSKC` 嵌套或平铺、大小写混用），
     * 把条目与 XSKC 合并后按别名不区分大小写取值；认不出课程名/成绩的条目跳过。
     */
    internal fun parseZhengFangGrades(body: String): List<GradeRecord> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val xskc = item["XSKC"] as? JsonObject
            // XSKC 嵌套字段权威，合并时覆盖外层；JsonObject(Map) 重建以保住类型
            val flat: JsonObject =
                if (xskc != null) {
                    JsonObject(item + xskc)
                } else {
                    item
                }
            val name = flat.strAlias("kcmc", "kc", "courseName").trim()
            if (name.isEmpty()) return@mapNotNull null
            val xn = flat.strAlias("xnmc", "xn", "xnxqdm").trim()
            val xq = flat.strAlias("xqmc", "xq").trim()
            GradeRecord(
                term = listOf(xn, xq).filter { it.isNotBlank() }.joinToString("-"),
                code = flat.strAlias("kch", "KCH").trim(),
                name = name,
                score = flat.strAlias("cj", "xmCj", "zpcj", "bfzcj").trim(),
                scoreFlag = "",
                credit = flat.strAlias("xf", "XF").trim().toDoubleOrNull() ?: 0.0,
                hours = null,
                gpa = flat.strAlias("jd", "JD").trim().toDoubleOrNull(),
                retakeTerm = "",
                examMode = "",
                examNature = "",
                attr = "",
                category = "",
            )
        }
    }

    /** 不区分大小写取第一个非空字段（正方各版本键名大小写混用） */
    private fun JsonObject.strAlias(
        vararg keys: String,
    ): String {
        val lowered = keys.map { it.lowercase() }.toSet()
        for ((key, value) in this) {
            if (key.lowercase() in lowered) {
                (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
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

    private fun post(
        client: OkHttpClient,
        url: String,
        referer: String,
        xnm: String,
        xqm: String,
    ): String {
        val form =
            FormBody
                .Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("kzlx", "ck")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(form)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }

    /** 课表页 <select id="xnm"> / <select id="xqm"> 的当前选中项 */
    private fun parseSelectedTerm(html: String): Pair<String, String>? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val xnm =
            doc
                .selectFirst("select#xnm option[selected]")
                ?.attr("value")
                ?.trim()
                .orEmpty()
        val xqm =
            doc
                .selectFirst("select#xqm option[selected]")
                ?.attr("value")
                ?.trim()
                .orEmpty()
        return if (xnm.isNotEmpty() && xqm.isNotEmpty()) xnm to xqm else null
    }

    /**
     * 按当前日期推断学年/学期（仅在没有页面选中项时使用）：
     * - 9 月–次年 1 月：第一学期（xqm=3）
     * - 2 月–8 月：第二学期（xqm=12），并补一个第一学期候选
     */
    internal fun termCandidates(today: LocalDate = LocalDate.now()): List<Pair<String, String>> {
        val firstYear = if (today.monthValue >= 9) today.year else today.year - 1
        val academicYear = "$firstYear-${firstYear + 1}"
        return if (today.monthValue in 2..8) {
            listOf(academicYear to "12", academicYear to "3")
        } else {
            listOf(academicYear to "3", academicYear to "12")
        }
    }

    /** 解析课表 JSON；非 JSON / 无 kbList 返回空列表，由调用方决定是否继续尝试 */
    fun parseJson(
        json: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["kbList"] as? JsonArray ?: return emptyList()
        val drafts =
            list.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.str("kcmc").replace(Regex("\\s+"), " ").trim()
                if (name.isEmpty()) return@mapNotNull null
                val day = parseDay(obj.str("xqj")) ?: return@mapNotNull null
                val sectionRaw = obj.str("jc").ifBlank { obj.str("jcs") }
                val sections = parseSections(sectionRaw) ?: return@mapNotNull null
                val weekRaw = obj.str("zcd").ifBlank { obj.str("zcmc") }
                val mask = WeekMask.parseExpression(totalWeeks, weekRaw) ?: WeekMask.of(totalWeeks)
                CourseDraft(
                    name = name,
                    teacher = obj.str("xm").trim(),
                    room = obj.str("cdmc").ifBlank { obj.str("xqmc") }.trim(),
                    dayOfWeek = day,
                    startSection = sections.first.coerceIn(1, 12),
                    endSection = sections.last.coerceIn(1, 12),
                    weekMask = mask,
                )
            }
        return mergeDuplicates(drafts)
    }

    /**
     * 同一门课可能按周次拆成多条（如 1-8 周、9-16 周），按
     * 课程/星期/节次/教师/教室 合并周次，避免预览里出现重复行。
     */
    internal fun mergeDuplicates(drafts: List<CourseDraft>): List<CourseDraft> {
        val merged = LinkedHashMap<String, CourseDraft>()
        drafts.forEach { draft ->
            val key =
                listOf(
                    draft.name,
                    draft.dayOfWeek,
                    draft.startSection,
                    draft.endSection,
                    draft.teacher,
                    draft.room,
                ).joinToString("|")
            val old = merged[key]
            merged[key] =
                if (old == null) {
                    draft
                } else {
                    old.copy(weekMask = orMask(old.weekMask, draft.weekMask))
                }
        }
        return merged.values.toList()
    }

    private fun orMask(
        a: String,
        b: String,
    ): String {
        val length = maxOf(a.length, b.length)
        val chars =
            CharArray(length) { i ->
                if (a.getOrNull(i) == '1' || b.getOrNull(i) == '1') '1' else '0'
            }
        return String(chars)
    }

    private fun parseDay(raw: String): Int? {
        Regex("""\d+""")
            .find(raw)
            ?.value
            ?.toIntOrNull()
            ?.let { return it.coerceIn(1, 7) }
        val zh = listOf("一", "二", "三", "四", "五", "六", "日", "天")
        zh.forEachIndexed { i, c -> if (raw.contains(c)) return (i + 1).coerceAtMost(7) }
        return null
    }

    private fun parseSections(raw: String): IntRange? {
        val nums =
            Regex("""\d+""")
                .findAll(raw)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
        if (nums.isEmpty()) return null
        return nums.min()..nums.max()
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
