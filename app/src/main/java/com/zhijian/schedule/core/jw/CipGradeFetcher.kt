package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * CIP 智慧校园（gxgc 等）的学生成绩抓取。
 *
 * 2026-10 实测协议，三步一循环：
 * ① `GET /teaching/score/view/teachingStudentScoreViewV2` —— 页面 meta `_csrf`；
 * ② `POST /school/common/getSchoolBaseInfo`（semester）—— 全部学期 ID 与名字；
 * ③ `POST /teaching/score/view/getStudentScoreListV2` `{semesterIds:"a,b,…"}`
 *    —— `data[].{semesterId, scoreList[]}`，条目字段照抄页面 v2.js 模板。
 *
 * CSRF 强制（实测无头 403）；403 视为 token 轮换，重取一次页面再试。
 * CIP 未提供学分与绩点（v2.js 无引用）→ 置 0 / 空，加权统计自动跳过。
 */
internal object CipGradeFetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 登录页的招牌文案（与 GuangXiGcAdapter 同源）；见到即视为未登录 */
    private const val LOGIN_MARK = "系统登录"
    private val CSRF_META = Regex("""<meta\s+name="_csrf"\s+content="([^"]+)"""")
    private const val SEMESTER_API = "school/common/getSchoolBaseInfo"
    private const val SCORE_PAGE = "teaching/score/view/teachingStudentScoreViewV2"
    private const val SCORE_API = "teaching/score/view/getStudentScoreListV2"

    suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> {
        val root = JwUrlGuard.safeHttpUrl(baseUrl)?.let { GuangXiGcAdapter.portalRoot(it.toString()) }
            ?: return emptyList()
        return withContext(Dispatchers.IO) { fetchCipGrades(client, root) }
    }

    private fun fetchCipGrades(
        client: OkHttpClient,
        root: String,
    ): List<GradeRecord> {
        val referer = root + SCORE_PAGE
        var csrf = scorePageCsrf(client, root)
        val semesterNames =
            try {
                parseSemesterMap(
                    post(client, root + SEMESTER_API, csrf, referer, """{"dataTypes":"semester"}"""),
                )
            } catch (ignored: JwException) {
                emptyMap()
            } catch (ignored: java.io.IOException) {
                emptyMap()
            }
        val body =
            try {
                post(client, root + SCORE_API, csrf, referer, scoreListBody(semesterNames.keys))
            } catch (ignored: JwException.NetworkFailed) {
                // token 轮换/过期：重取一次页面，用新 csrf 再试（仅一次）
                csrf = scorePageCsrf(client, root)
                post(client, root + SCORE_API, csrf, referer, scoreListBody(semesterNames.keys))
            }
        return parseCipScores(body, semesterNames)
    }

    /** 拉成绩列表；semesterIds 为空时照发（服务端按学生本人过滤，多半返回空） */
    private fun scoreListBody(semesterIds: Collection<String>): String =
        """{"semesterIds":"${semesterIds.joinToString(",")}"}"""

    /** 成绩页 meta `_csrf`；页面是登录页抛 NeedsLogin，没有 token 视为结构变化 */
    private fun scorePageCsrf(
        client: OkHttpClient,
        root: String,
    ): String {
        val page = get(client, root + SCORE_PAGE, root + "sys/portal")
        if (page.contains(LOGIN_MARK)) throw JwException.NeedsLogin()
        return CSRF_META.find(page)?.groupValues?.get(1)
            ?: throw JwException.ParseFailed("成绩页缺少请求令牌")
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
        csrf: String,
        referer: String,
        json: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-TOKEN", csrf)
                .post(json.toRequestBody(JSON_MEDIA))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }

    /** 学期列表 → id 到名字的映射（成绩抓取的 term 名与全学期 ID 集） */
    internal fun parseSemesterMap(body: String): Map<String, String> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val data = root["data"] as? JsonObject ?: return emptyMap()
        val list = data["semesterList"] as? JsonArray ?: return emptyMap()
        return list
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val id = obj.str("semesterId")
                val name = obj.str("semesterName").trim()
                if (id.isBlank() || name.isBlank()) null else id to name
            }.toMap()
    }

    /**
     * 成绩 JSON → 成绩记录。`data[].{semesterId, scoreList[]}`；条目字段照抄页面
     * v2.js 模板（`v-text="courseScore.courseName"` 等）。`sumScore` 可能是数字
     * （百分制）也可能是等级文本（displayType=2），原文照收；examStatus 非 0 时
     * 映射成「缺考/作弊/…」文案进 scoreFlag（文案取自 v2.js 的 examStatus 数组）。
     */
    internal fun parseCipScores(
        body: String,
        semesterNames: Map<String, String>,
    ): List<GradeRecord> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()

        return data
            .mapNotNull { semester -> parseSemesterScores(semester as? JsonObject, semesterNames) }
            .flatten()
    }

    private fun parseSemesterScores(
        semester: JsonObject?,
        semesterNames: Map<String, String>,
    ): List<GradeRecord>? {
        if (semester == null) return null
        val term = semesterNames[semester.str("semesterId")].orEmpty()
        val list = semester["scoreList"] as? JsonArray ?: return null
        return list.mapNotNull { element -> parseScoreItem(element as? JsonObject, term) }
    }

    private fun parseScoreItem(
        item: JsonObject?,
        term: String,
    ): GradeRecord? {
        if (item == null) return null
        val name = item.str("courseName").trim()
        if (name.isEmpty()) return null
        val status = item.int("examStatus") ?: 0
        return GradeRecord(
            term = term,
            code = "",
            name = name,
            score = item.str("sumScore").trim(),
            scoreFlag = EXAM_STATUS_FLAGS.getOrNull(status - 1).orEmpty(),
            credit = 0.0,
            hours = null,
            gpa = null,
            retakeTerm = "",
            examMode = "",
            examNature = "",
            attr = "",
            category = "",
        )
    }

    /** examStatus：0 正常；1 缺考 2 作弊 3 缓考 4 免修 5 免考 6 取消考试资格（v2.js 数组） */
    private val EXAM_STATUS_FLAGS =
        listOf("缺考", "作弊", "缓考", "免修", "免考", "取消考试资格")

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.trim().toIntOrNull()
    }
}
