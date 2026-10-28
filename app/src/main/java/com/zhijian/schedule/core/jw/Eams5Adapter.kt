package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 树维 eams5（supwisdom EAMS5，`/eams5-student/`、`/for-std/course-table`）。
 *
 * 与 [ShuWeiClassicAdapter] 不是同一套接口：eams5 是 SPA + JSON 三段式，
 * 缺一不可——
 * 1. `GET for-std/course-table` 页面里抠 `studentId` / `semesterId`；
 * 2. `GET for-std/course-table/print-data/{semesterId}/{studentId}` 取打印数据；
 * 3. 兜底 `get-data` → `POST ws/schedule-table/datum`（`lessonIds` + `weekIndex`）。
 *
 * **刻意不猜字段**：print-data / datum 在不同部署里结构差异很大（HFUT、AHU、NEU
 * 三家都不同），因此只认「响应里含 HTML 课表表格」的形态，统一交给
 * [HtmlTableScheduleParser]；认不出就报 ParseFailed 让用户先进课表页，
 * 绝不按猜测的 JSON 字段拼课程。
 */
object Eams5Adapter : JwAdapter {
    override val id = "eams5"
    override val name = "树维 eams5"

    override val steps =
        AdapterSteps(
            timetablePage = "我的课表",
            note = "eams5 是单页应用，等课表格子真正画出来再点抓取",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val EAMS5_STUDENT = "/eams5-student/"
    private const val STUDENT_ONLY = "/student/"
    private const val TABLE_MARK = "<table"
    private const val JSON_MEDIA = "application/json; charset=utf-8"
    private const val DEFAULT_WEEK_INDEX = 0
    private const val MAX_JSON_DEPTH = 8
    private const val JSON_OBJECT = '{'
    private const val JSON_ARRAY = '['

    private val STUDENT_ID = Regex("""(?:studentId|dataId|stdId)["']?\s*[:=]\s*["']?(\d{2,12})""")
    private val SEMESTER_ID = Regex("""(?:semesterId|semId)["']?\s*[:=]\s*["']?(\d{1,8})""")

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("for-std/course-table") -> JwMatchScore.EXACT
            path.contains(EAMS5_STUDENT) -> JwMatchScore.EXACT
            path.contains("/eams5/") -> JwMatchScore.STRONG
            SchoolCatalog.byHost(parsed.host)?.adapterId == id -> JwMatchScore.CATALOG
            else -> JwMatchScore.NONE
        }
    }

    @Suppress("SwallowedException")
    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            val pageUrl = courseTableUrl(baseUrl, base)
            val page =
                try {
                    get(client, pageUrl, base)
                } catch (e: IOException) {
                    throw JwException.NetworkFailed(e.message ?: "eams5 页面打不开")
                }
            if (isLoginPage(page)) throw JwException.NeedsLogin()

            // eams5 的时间信息跟课表格子在同一份 HTML 里，随每一条路一起带回来
            val draft = fetchByPrintData(client, base, page, totalWeeks)
            if (draft.courses.isNotEmpty()) return@withContext draft

            val byDatum = fetchByDatum(client, base, page, totalWeeks)
            if (byDatum.courses.isNotEmpty()) return@withContext byDatum

            val inline = HtmlTableScheduleParser.parse(page, totalWeeks)
            if (inline.isNotEmpty()) {
                return@withContext JwFetchResult(inline, SectionTimes.fromHtml(page))
            }

            throw JwException.ParseFailed(
                "eams5 未返回可解析的课表：请先在网页中进入「我的课表」再导入",
            )
        }

    /** 第二段：`print-data` 打印数据（最稳的那条路） */
    @Suppress("SwallowedException")
    private fun fetchByPrintData(
        client: OkHttpClient,
        base: String,
        page: String,
        totalWeeks: Int,
    ): JwFetchResult {
        val ids = idsOf(page) ?: return JwFetchResult(emptyList())
        val url = "${base}for-std/course-table/print-data/${ids.second}/${ids.first}"
        val body =
            try {
                get(client, url, base)
            } catch (e: IOException) {
                return JwFetchResult(emptyList())
            }
        return parsePayloadWithTimes(body, totalWeeks)
    }

    /** 第三段兜底：`get-data` 拿 lessonIds，再 POST `datum` 取课表文本 */
    @Suppress("SwallowedException")
    private fun fetchByDatum(
        client: OkHttpClient,
        base: String,
        page: String,
        totalWeeks: Int,
    ): JwFetchResult {
        val ids = idsOf(page) ?: return JwFetchResult(emptyList())
        val body = datumBody(client, base, ids) ?: return JwFetchResult(emptyList())
        return parsePayloadWithTimes(body, totalWeeks)
    }

    /** 同 [parsePayload]，但同时把 HTML 里的时间文字抽成作息 */
    private fun parsePayloadWithTimes(
        body: String,
        totalWeeks: Int,
    ): JwFetchResult {
        val drafts = parsePayload(body, totalWeeks)
        if (drafts.isEmpty()) return JwFetchResult(emptyList())
        val htmlBlocks = htmlFragmentsOf(body) + body
        val times =
            htmlBlocks
                .firstNotNullOfOrNull { html ->
                    SectionTimes.fromHtml(html).takeIf { it.isNotEmpty() }
                }.orEmpty()
        return JwFetchResult(drafts, times)
    }

    /** JSON 信封里内嵌的 HTML 片段；不是 JSON 时返回空，交给调用方用原文 */
    private fun htmlFragmentsOf(body: String): List<String> {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith(JSON_OBJECT) && !trimmed.startsWith(JSON_ARRAY)) return emptyList()
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return htmlStrings(root)
    }

    /** `get-data` 取 lessonIds，再 POST `datum`；任一步失败返回 null，交给上层的页面兜底 */
    @Suppress("SwallowedException")
    private fun datumBody(
        client: OkHttpClient,
        base: String,
        ids: Pair<String, String>,
    ): String? {
        val dataUrl =
            "${base}for-std/course-table/get-data?bizTypeId=2&semesterId=${ids.second}&dataId=${ids.first}"
        val lessonIds =
            try {
                lessonIdsOf(get(client, dataUrl, base))
            } catch (e: IOException) {
                null
            } ?: return null
        val payload =
            """{"lessonIds":$lessonIds,"studentId":"${ids.first}","weekIndex":$DEFAULT_WEEK_INDEX}"""
        return try {
            post(client, "${base}ws/schedule-table/datum", payload, base)
        } catch (e: IOException) {
            null
        }
    }

    /** 从课表页脚本里抠 (studentId, semesterId)；缺任意一个就无法走 JSON 两段式 */
    private fun idsOf(html: String): Pair<String, String>? {
        val studentId = STUDENT_ID.find(html)?.groupValues?.get(1) ?: return null
        val semesterId = SEMESTER_ID.find(html)?.groupValues?.get(1) ?: return null
        return studentId to semesterId
    }

    private fun lessonIdsOf(json: String): String? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val ids = root["lessonIds"] as? JsonArray ?: return null
        if (ids.isEmpty()) return null
        return ids.toString()
    }

    /**
     * 只认「响应里带 HTML 表格」这一种形态：JSON 里任何字符串字段含 `<table`
     * 就取出来交给通用解析器。认不出返回空，由调用方继续下一条路。
     */
    fun parsePayload(
        body: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val trimmed = body.trimStart()
        // JSON 里可能内嵌 HTML 片段，必须先按 JSON 走，否则整段 JSON 会被当成 HTML
        val root =
            if (trimmed.startsWith(JSON_OBJECT) || trimmed.startsWith(JSON_ARRAY)) {
                runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            } else {
                null
            }
        if (root == null) return HtmlTableScheduleParser.parse(body, totalWeeks)
        for (html in htmlStrings(root)) {
            val drafts = HtmlTableScheduleParser.parse(html, totalWeeks)
            if (drafts.isNotEmpty()) return drafts
        }
        return emptyList()
    }

    /** 深度遍历 JSON，收集所有「像 HTML 表格」的字符串值 */
    private fun htmlStrings(element: JsonElement): List<String> {
        val found = mutableListOf<String>()
        collectHtml(element, found, 0)
        return found
    }

    private fun collectHtml(
        element: JsonElement,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth > MAX_JSON_DEPTH) return
        when (element) {
            is JsonObject -> element.values.forEach { collectHtml(it, out, depth + 1) }
            is JsonArray -> element.forEach { collectHtml(it, out, depth + 1) }
            is JsonPrimitive -> {
                val text = element.contentOrNull.orEmpty()
                if (text.contains(TABLE_MARK, ignoreCase = true)) out += text
            }
        }
    }

    private fun courseTableUrl(
        baseUrl: String,
        base: String,
    ): String {
        val trimmed = baseUrl.trim()
        if (trimmed.contains("for-std/course-table", ignoreCase = true)) return trimmed
        return "${base}for-std/course-table"
    }

    private fun isLoginPage(html: String): Boolean = isSessionDeadPage(html)

    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val eams5 = path.indexOf(EAMS5_STUDENT)
        val student = path.indexOf(STUDENT_ONLY)
        val basePath =
            when {
                eams5 >= 0 -> path.substring(0, eams5 + EAMS5_STUDENT.length)
                student >= 0 -> path.substring(0, student + STUDENT_ONLY.length)
                else -> SchoolCatalog.knownBasePath(parsed.host) ?: EAMS5_STUDENT
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
            if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
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
                .header("Accept", "*/*")
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
        json: String,
        referer: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(json.toRequestBody(JSON_MEDIA.toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}
