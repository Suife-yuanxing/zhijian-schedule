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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * 超星综合教务适配器（`*.jw.chaoxing.com` / `*.fanya.chaoxing.com` 校级租户）。
 *
 * 登录形态多样（校级 CAS 跳 `admin/caslogin`、超星账号直登、学习通扫码），
 * 统一交给 WebView；适配器只带共享 Cookie 重放：
 *
 * ① `GET /admin/` —— 任意门户页都带 `encodeId`（隐藏域），同时用来判登录态；
 * ② `GET /admin/pkgl/xskb/sdpkkbList?xnxq=&xhid=` —— 整学期课表 JSON，
 *    条目字段：kcmc / tmc（教师）/ croommc / xingqi / djc / zcstr；
 * ③ `GET /admin/xsd/xsdcjcx/xsdQueryXscjList` —— jqGrid 全部成绩
 *    （字段由 queryFields 声明：xnxq / kcmc / xf / zhcj / tscjzwmc / sfbk…）。
 *
 * 学期参数 `xnxq` 形如 `2025-2026-1`：优先从门户页抠，抠不到按日期推算，
 * 候选逐个试（第一学期 9-1 月，第二学期 2-7 月）。
 */
object ChaoXingAdapter : JwAdapter {
    override val id = "chaoxing"
    override val name = "超星教务"

    override val steps =
        AdapterSteps(
            timetablePage = "教务门户（任意页面）",
            note = "登录后停在教务门户里即可，无需进入具体课表页",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val HOST_SUFFIX_JW = ".jw.chaoxing.com"
    private const val HOST_SUFFIX_FANYA = ".fanya.chaoxing.com"
    private const val ADMIN = "admin/"
    private const val TIMETABLE_API = "admin/pkgl/xskb/sdpkkbList"
    private const val GRADE_API = "admin/xsd/xsdcjcx/xsdQueryXscjList"
    private const val GRADE_PAGE = "admin/xsd/xsdcjcx/qbcjcx"
    private const val PAGE_SIZE = 500
    private const val AUGUST = 8
    private const val FEBRUARY = 2
    private const val JULY = 7
    private const val MIN_DAY = 1
    private const val MAX_DAY = 7

    private val ENCODE_ID = Regex("""id="encodeId"[^>]*value="([^"]+)"""")
    private val SEMESTER_ON_PAGE = Regex("""(\d{4}-\d{4}-[12])""")
    private val HTML_TAG = Regex("""<[^>]*>""")

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        val host = parsed.host.lowercase()
        return when {
            host.endsWith(HOST_SUFFIX_JW) || host.endsWith(HOST_SUFFIX_FANYA) -> JwMatchScore.EXACT
            SchoolCatalog.byHost(parsed.host)?.adapterId == id -> JwMatchScore.CATALOG
            else -> JwMatchScore.NONE
        }
    }

    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val root = tenantRoot(baseUrl) ?: throw JwException.NetworkFailed("教务地址无效")
            val referer = root + ADMIN
            val page =
                try {
                    get(client, root + ADMIN, referer)
                } catch (ignored: IOException) {
                    throw JwException.NetworkFailed("教务门户连接失败")
                }
            if (isLoginPage(page)) throw JwException.NeedsLogin()
            val xhid =
                ENCODE_ID
                    .find(page)
                    ?.groupValues
                    ?.get(1)
                    ?: throw JwException.ParseFailed("门户页缺少会话参数，请先登录教务门户")

            for (xnxq in semesterCandidates(page)) {
                val body =
                    try {
                        get(client, timetableUrl(root, xnxq, xhid), referer, xmlHttp = true)
                    } catch (ignored: IOException) {
                        continue
                    }
                val drafts = parseTimetable(body, totalWeeks)
                if (drafts.isNotEmpty()) {
                    return@withContext JwFetchResult(drafts, emptyList(), JwMeta(semester = xnxq))
                }
                if (needsLoginJson(body)) throw JwException.NeedsLogin()
            }
            throw JwException.ParseFailed("未解析到课程：请确认该账号本学期有排课")
        }

    private fun timetableUrl(
        root: String,
        xnxq: String,
        xhid: String,
    ): String =
        (root + TIMETABLE_API)
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("xnxq", xnxq)
            ?.addQueryParameter("xhid", xhid)
            ?.build()
            .toString()

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：jqGrid 接口一次拉全（page.size 放大），字段按 queryFields 声明。
     * 结构不认识 / 未登录时返回空，按未抓到静默跳过。
     */
    override suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> =
        withContext(Dispatchers.IO) {
            val root = JwUrlGuard.safeHttpUrl(baseUrl)?.let { tenantRoot(it.toString()) }
            val body =
                root?.let { safeRoot ->
                    gradeUrl(safeRoot)?.let { url ->
                        runCatching { get(client, url, safeRoot + GRADE_PAGE, xmlHttp = true) }.getOrNull()
                    }
                }
            when {
                body == null || isLoginPage(body) || needsLoginJson(body) -> emptyList()
                else -> parseGrades(body)
            }
        }

    private fun gradeUrl(root: String): String? =
        (root + GRADE_API)
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("fxbz", "0")
            ?.addQueryParameter("gridtype", "jqgrid")
            ?.addQueryParameter("queryFields", GRADE_FIELDS)
            ?.addQueryParameter("_search", "false")
            ?.addQueryParameter("nd", System.currentTimeMillis().toString())
            ?.addQueryParameter("page.size", PAGE_SIZE.toString())
            ?.addQueryParameter("page.pn", "1")
            ?.addQueryParameter("sort", "xnxq desc,id")
            ?.addQueryParameter("order", "desc")
            ?.build()
            .toString()

    private const val GRADE_FIELDS =
        "id,xnxq,kcmc,xf,kcxz,kclx,ksxs,kcgs,xdxz,kclb,cjfxms,zhcj,hdxf,tscjzwmc,sfbk,cjlrjsxm,kcsx,fxcj"

    /** 学期候选：门户页里写着的在前，按日期推算的（本学期 → 另一学期）殿后 */
    internal fun semesterCandidates(page: String): List<String> {
        val fromPage =
            SEMESTER_ON_PAGE
                .findAll(page)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        val derived = derivedSemester()
        val flipped =
            when (derived.last()) {
                '1' -> derived.dropLast(1) + "2"
                else -> derived.dropLast(1) + "1"
            }
        return (listOf(derived, flipped) + fromPage).distinct()
    }

    /** 8-12 月为学年第一学期，1 月仍属上一学年第一学期，2-7 月为第二学期 */
    private fun derivedSemester(): String {
        val today = java.time.LocalDate.now()
        val startYear = if (today.monthValue >= AUGUST) today.year else today.year - 1
        val term = if (today.monthValue in FEBRUARY..JULY) "2" else "1"
        return "$startYear-${startYear + 1}-$term"
    }

    /** sdpkkbList 条目：一门课的一个小节；kcmc/tmc/croommc 可能是 HTML 片段 */
    internal fun parseTimetable(
        body: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root.int("ret") != 0) return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return merge(data.mapNotNull { draftOf(it as? JsonObject, totalWeeks) })
    }

    private fun draftOf(
        item: JsonObject?,
        totalWeeks: Int,
    ): CourseDraft? {
        val name = plain(item?.str("kcmc").orEmpty())
        val day = item?.int("xingqi")
        val section = item?.int("djc")
        val weeks = expandWeeks(item?.str("zcstr").orEmpty())
        val valid =
            item != null &&
                name.isNotEmpty() &&
                day != null &&
                day in MIN_DAY..MAX_DAY &&
                section != null &&
                section in 1..SectionTimes.MAX_SECTIONS &&
                weeks.isNotEmpty()
        if (!valid) return null
        return CourseDraft(
            name = name,
            teacher = plain(item.str("tmc")),
            room = plain(item.str("croommc")),
            dayOfWeek = day,
            startSection = section,
            endSection = section,
            weekMask = WeekMask.fromWeeks(totalWeeks, weeks),
        )
    }

    /** 同一天 + 同名 + 同周次的连续小节合并成一个连堂 */
    private fun merge(drafts: List<CourseDraft>): List<CourseDraft> {
        val sorted = drafts.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
        val out = mutableListOf<CourseDraft>()
        for (draft in sorted) {
            val last = out.lastOrNull()
            val continues =
                last != null &&
                    last.dayOfWeek == draft.dayOfWeek &&
                    last.name == draft.name &&
                    last.teacher == draft.teacher &&
                    last.room == draft.room &&
                    last.weekMask == draft.weekMask &&
                    draft.startSection == last.endSection + 1
            if (continues) {
                out[out.size - 1] = last.copy(endSection = draft.endSection)
            } else {
                out += draft
            }
        }
        return out
    }

    /** zcstr：逗号分隔的周次，兼容「1-4」区间写法 */
    private fun expandWeeks(raw: String): List<Int> {
        val weeks = mutableListOf<Int>()
        raw.split(",").forEach { part ->
            val text = part.trim()
            val range = Regex("""(\d+)\s*[-~]\s*(\d+)""").find(text)
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull()
                val b = range.groupValues[2].toIntOrNull()
                if (a != null && b != null && a in 1..b) weeks += a..b
            } else {
                text.toIntOrNull()?.takeIf { it > 0 }?.let { weeks += it }
            }
        }
        return weeks.distinct().sorted()
    }

    /** jqGrid rows → 成绩记录；总评成绩为空时用特殊成绩文案（缺考 / 及格…） */
    internal fun parseGrades(body: String): List<GradeRecord> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val rows = root["rows"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val name = plain(item.str("kcmc"))
            if (name.isEmpty()) return@mapNotNull null
            val special = plain(item.str("tscjzwmc"))
            val rawScore = item.str("zhcj").trim()
            val score = rawScore.ifEmpty { special }
            GradeRecord(
                term = item.str("xnxq"),
                code = "",
                name = name,
                score = score,
                scoreFlag =
                    when {
                        item.str("sfbk") == "1" -> "补考"
                        rawScore.isNotEmpty() && special.isNotEmpty() -> special
                        else -> ""
                    },
                credit = item.str("xf").trim().toDoubleOrNull() ?: 0.0,
                hours = null,
                gpa = null,
                retakeTerm = "",
                examMode = "",
                examNature = "",
                attr = item.str("kcxz"),
                category = item.str("kclx"),
            )
        }
    }

    private fun needsLoginJson(body: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
        val msg = root.str("msg")
        return root.int("ret") != 0 && msg.contains("登录")
    }

    /** 超星门户登录页：用户名/密码表单，或被重定向到 admin/login */
    private fun isLoginPage(html: String): Boolean =
        html.contains("admin/login", ignoreCase = true) ||
            (html.contains("type=\"password\"", ignoreCase = true) && html.contains("登录"))

    /** 租户根：`https://<校>.jw.chaoxing.com/`；非租户域返回 null */
    internal fun tenantRoot(url: String): String? {
        val parsed = parseUrl(url) ?: return null
        val host = parsed.host.lowercase()
        if (!host.endsWith(HOST_SUFFIX_JW) && !host.endsWith(HOST_SUFFIX_FANYA)) return null
        return parsed
            .newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun plain(raw: String): String =
        Jsoup
            .parseBodyFragment(raw.replace(HTML_TAG, " "))
            .text()
            .replace('\u00A0', ' ')
            .trim()

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
        xmlHttp: Boolean = false,
    ): String {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
        if (xmlHttp) {
            builder.header("X-Requested-With", "XMLHttpRequest")
            builder.header("Accept", "application/json, text/plain, */*")
        }
        builder.get()
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? {
    val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
    return raw.trim().toIntOrNull()
}
