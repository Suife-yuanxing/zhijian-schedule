package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * URP 综合教务适配器，覆盖两代实现：
 *
 * - **新 URP（`dateList` JSON）**：`showTimetable.do` 返回
 *   `{ dateList: [ { selectCourseList: [ { courseName, attendClassTeacher,
 *   timeAndPlaceList: [ { classDay, classSessions, continuingSession, classWeek,
 *   teachingBuildingName, classroomName, campusName } ] } ] } ] }`
 * - **老 URP（HTML 表格）**：`showTimetable.do` 直出「星期 × 节次」大表，
 *   交给 [HtmlTableScheduleParser]。
 *
 * 登录仍由 WebView 完成。老 URP 的课表页往往需要 `timetableType=STD` 与学期参数，
 * 因此优先复用用户在网页里实际停留的课表页地址（见 `JwSyncManager.fetchForPreview`
 * 的 `urlOverride`），候选路径只作兜底。
 */
object UrpAdapter : JwAdapter {
    override val id = "urp"
    override val name = "URP 综合教务"

    override val steps =
        AdapterSteps(
            timetablePage = "个人管理 → 本学期课表",
            note = "老版 URP 要先选定学期；地址栏出现 showTimetable.do 才算进对页",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val DEFAULT_SPAN = 1
    private const val MIN_DAY = 1
    private const val MAX_DAY = 7
    private const val MIN_SECTION = 1
    private const val MAX_SECTION = 12
    private const val JSON_PREFIX = '{'
    private const val ARRAY_PREFIX = '['
    private const val ACADEMIC = "/academic/"

    /** 课表页常见路径（相对 /academic/）；按部署新旧排列 */
    private val TIMETABLE_PATHS =
        listOf(
            "manager/coursearrange/showTimetable.do?timetableType=STD",
            "manager/coursearrange/showTimetable.do",
            "coursearrange/showTimetable.do?timetableType=STD",
            "manager/coursearrange/myTimetable.do",
        )

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("showtimetable.do") -> JwMatchScore.EXACT
            path.contains(ACADEMIC) || path.endsWith("/academic") -> JwMatchScore.STRONG
            path.contains("xkaction.do") -> JwMatchScore.ENDPOINT
            SchoolCatalog.byHost(parsed.host)?.adapterId == id -> JwMatchScore.CATALOG
            else -> JwMatchScore.NONE
        }
    }

    override suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): List<CourseDraft> =
        withContext(Dispatchers.IO) {
            val referer = refererOf(baseUrl)
            val targets = targetsOf(baseUrl)
            var lastError: String? = null
            for (target in targets) {
                val body =
                    try {
                        get(client, target, referer)
                    } catch (e: JwException) {
                        throw e
                    } catch (e: IOException) {
                        lastError = e.message
                        continue
                    }
                if (isLoginPage(body)) throw JwException.NeedsLogin()
                val drafts = parseBody(body, totalWeeks)
                if (drafts.isNotEmpty()) return@withContext drafts
                lastError = "未解析到课程：$target"
            }
            throw JwException.ParseFailed(
                "URP 未返回课表：${lastError ?: "请先在网页中进入「本学期课表」再导入"}",
            )
        }

    /** 已经停在课表页时直接请求该页；否则退回候选路径 */
    private fun targetsOf(baseUrl: String): List<String> {
        val direct = baseUrl.trim()
        if (direct.contains("showTimetable", ignoreCase = true)) return listOf(direct)
        val base = normalizeBase(baseUrl)
        return TIMETABLE_PATHS.map { base + it }
    }

    private fun refererOf(baseUrl: String): String =
        runCatching { "${normalizeBase(baseUrl)}common/security/login.jsp" }
            .getOrDefault(baseUrl)

    /** 先按 JSON 认，再退回通用表格解析；两者互不干扰 */
    fun parseBody(
        body: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val trimmed = body.trimStart()
        val drafts =
            when {
                trimmed.startsWith(JSON_PREFIX) || trimmed.startsWith(ARRAY_PREFIX) ->
                    parseJson(body, totalWeeks)
                else -> HtmlTableScheduleParser.parse(body, totalWeeks)
            }
        return drafts
    }

    /** 新 URP 的 `dateList[].selectCourseList[].timeAndPlaceList[]` */
    fun parseJson(
        json: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val dates = root["dateList"] as? JsonArray ?: return emptyList()
        val drafts = mutableListOf<CourseDraft>()
        for (element in dates) {
            val dateObj = element as? JsonObject ?: continue
            drafts += dateDrafts(dateObj, totalWeeks)
        }
        return drafts
    }

    private fun dateDrafts(
        dateObj: JsonObject,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val courses = dateObj["selectCourseList"] as? JsonArray ?: return emptyList()
        return courses.flatMap { courseDrafts(it, totalWeeks) }
    }

    private fun courseDrafts(
        element: JsonElement?,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val course = element as? JsonObject ?: return emptyList()
        val name = course.str("courseName").trim()
        if (name.isEmpty()) return emptyList()
        val teacher = course.str("attendClassTeacher").trim()
        val places = course["timeAndPlaceList"] as? JsonArray ?: return emptyList()
        return places.mapNotNull { placeDraft(it, name, teacher, totalWeeks) }
    }

    private fun placeDraft(
        place: JsonElement?,
        name: String,
        teacher: String,
        totalWeeks: Int,
    ): CourseDraft? {
        val obj = place as? JsonObject ?: return null
        val day = obj.int("classDay") ?: return null
        if (day !in MIN_DAY..MAX_DAY) return null
        val start = obj.int("classSessions") ?: return null
        val span = obj.int("continuingSession") ?: DEFAULT_SPAN
        val startSection = start.coerceIn(MIN_SECTION, MAX_SECTION)
        val endSection = (start + span - DEFAULT_SPAN).coerceIn(startSection, MAX_SECTION)
        val room =
            obj.str("campusName").trim() +
                obj.str("teachingBuildingName").trim() +
                obj.str("classroomName").trim()
        return CourseDraft(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startSection = startSection,
            endSection = endSection,
            weekMask = weekMaskOf(obj.str("classWeek"), obj.str("weekDescription"), totalWeeks),
        )
    }

    /**
     * `classWeek` 是位图字符串，但起点/长度一校一变：短了补 0，长了截断，
     * 空串时退回 `weekDescription`（如「1-16周」），都没有就整学期。
     */
    private fun weekMaskOf(
        bitmap: String,
        description: String,
        totalWeeks: Int,
    ): String {
        val bits = bitmap.filter { it == '0' || it == '1' }
        if (bits.isNotEmpty()) return WeekMask.normalize(bits, totalWeeks)
        return WeekMask.parseExpression(totalWeeks, description) ?: WeekMask.of(totalWeeks)
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.trim().toIntOrNull()
    }

    private fun isLoginPage(html: String): Boolean =
        isSessionDeadPage(html) ||
            (html.contains("name=\"password\"") && !html.contains("<table"))

    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val index = path.indexOf("/academic/")
        val basePath =
            when {
                index >= 0 -> path.substring(0, index + ACADEMIC.length)
                path.endsWith("/academic") -> "$path/"
                else -> SchoolCatalog.knownBasePath(parsed.host) ?: "/academic/"
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
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}
