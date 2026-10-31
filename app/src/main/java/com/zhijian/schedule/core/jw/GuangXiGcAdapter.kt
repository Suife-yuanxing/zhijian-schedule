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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 广西工程职业学院（`zhxy.gxgcedu.com` 智慧校园）的校级适配器。
 *
 * ## 为什么是原生 CIP 协议，而不是把抓取交给 URP
 * 校方 2022 年通知里的入口 `http://edu.gxgcedu.com/` 已经不解析（NXDOMAIN）；
 * 现行入口是智慧校园统一认证门户 `https://zhxy.gxgcedu.com/login`。2026 年 10 月实测：
 * 门户主机上的 `/academic/`（URP 特征路径）已经 **404**——教务是 CIP 平台自带的模块，
 * 与门户同域同源，路由形如 `/timetable/timetableView/studentTimetable`，
 * 再按 URP 抓只会得到 404。所以本适配器直接说 CIP 的协议：
 *
 * 1. `GET /timetable/timetableView/studentTimetable`——带着 WebView 登录后的会话即可；
 *    页面 `<meta name="_csrf">` 是后续 POST 的令牌，脚本里的 `defaultSemesterId` 是当前学期；
 * 2. `POST /timetable/timetableRule/getTimetableRule`——各时段节数（本校 2026 秋实测
 *    上午 4 / 下午 4 / 晚上 4），决定槽位到全天节次的偏移；
 * 3. `POST /timetable/timetableView/getStudentTimetable`，`{semesterId, weekNo:"1,2,…"}`
 *    （CSRF 强制，缺头直接 403）——`data.studentTimetableList[时段]["星期_节段"]` 下是课程
 *    条目，每条自带 `weekNos`（全学期周次列表），**一次请求就能建出全学期课表**。
 *
 * ## 槽位语义（照抄前端 `timetable.ctrls.js` 的解析）
 * key = `<星期几>_<节段>`，节段两位数字是「时段内 1 起的小节序号」；
 * 全天节次 = 时段偏移 + 时段内序号。偏移按 `getTimetableRule` 的节数逐时段累加
 * （早读/上午从第 1 节起，中午/下午/晚上依次接在前序时段之后）。
 * `1_34` 在本校即周一上午第 3-4 节；`pm/1_12` 即全天第 5-6 节。
 *
 * ## 作息时间
 * 教务里没有配置钟点（`getTimetableRuleTime` 返回空），官网文件也只写「每节课 45 分钟」。
 * 实测按 8:00 / 14:30 / 19:00 三段排课，写成 [SECTION_TIMES] 随导入带回——
 * 教务哪天配了钟点，就优先用教务返回的（见 [sectionTimesOf]）。
 *
 * ## 不做的事
 * 门户统一认证是 RSA 加密登录，有图形验证码与微信 / 企业微信 / 钉钉扫码，风险控制
 * 由校方把持，[ssoEntry] 一律返回 null：宁可让用户在 WebView 里点一次登录。
 */
object GuangXiGcAdapter : JwAdapter {
    override val id = "gxgc"
    override val name = "广西工程职业学院"

    override val steps =
        AdapterSteps(
            timetablePage = "门户登录 → 教务系统门户（出现课表入口即可）",
            note = "登录智慧校园门户后直接点抓取；学期、周次与作息自动识别",
        )

    /** 门户与教务同域同源；识别只看主机，任何路径都归本校 */
    const val HOST = "zhxy.gxgcedu.com"

    /** 实测当前学期的课表页路由；抓取从这里拿会话令牌与学期 */
    private const val TIMETABLE_PAGE = "timetable/timetableView/studentTimetable"
    private const val TIMETABLE_API = "timetable/timetableView/getStudentTimetable"
    private const val RULE_API = "timetable/timetableRule/getTimetableRule"
    private const val RULE_TIME_API = "timetable/timetableRule/getTimetableRuleTime"
    private const val WEEK_LIST_API = "school/core/calendar/getWeekList"
    private const val SEMESTER_API = "school/common/getSchoolBaseInfo"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private const val MIN_DAY = 1
    private const val MAX_DAY = 7

    /** weekNo 一次带全；学校学期短于请求值时由后端自己过滤 */
    private const val MIN_TOTAL_WEEKS = 18

    /** 登录页的招牌文案（inline script 里的 document.title）；见到即视为未登录 */
    private const val LOGIN_MARK = "系统登录"

    private val CSRF_META = Regex("""<meta\s+name="_csrf"\s+content="([^"]+)"""")
    private val SEMESTER_ID = Regex("""defaultSemesterId\s*=\s*"([^"]+)"""")

    /** `1_34` / `2_12`：星期几_时段内节段（两位数字，各自是 1 起的小节序号） */
    private val SLOT_KEY = Regex("""^(\d+)_([1-9])([1-9])$""")

    /**
     * 本校作息（2026 年 10 月实测确认；教务未配钟点）：
     * 每节 45 分钟，上午 8:00 起、下午 14:30 起、晚上 19:00 起，各四节。
     */
    internal val SECTION_TIMES: List<SectionTimeDraft> =
        listOf(
            "08:00" to "08:45",
            "08:55" to "09:40",
            "10:00" to "10:45",
            "10:55" to "11:40",
            "14:30" to "15:15",
            "15:25" to "16:10",
            "16:20" to "17:05",
            "17:15" to "18:00",
            "19:00" to "19:45",
            "19:55" to "20:40",
            "20:50" to "21:35",
            "21:45" to "22:30",
        ).mapIndexed { index, (start, end) -> SectionTimeDraft(index + 1, start, end) }

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        if (!parsed.host.equals(HOST, ignoreCase = true)) return JwMatchScore.NONE
        return JwMatchScore.EXACT
    }

    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val root = portalRoot(baseUrl)
            val referer = root + "sys/portal"

            // ① 课表页：会话令牌 + 当前学期
            val page = get(client, root + TIMETABLE_PAGE, referer)
            if (page.contains(LOGIN_MARK)) throw JwException.NeedsLogin()
            val (csrf, semesterId) =
                parseTimetablePage(page)
                    ?: throw JwException.ParseFailed(
                        "页面上没有学期信息，请先在网页中登录并进入「教务系统门户」",
                    )

            // ② 排课规则：决定槽位偏移；拿不到就按实测值（上午/下午/晚上各 4 节）兜底
            val hours =
                try {
                    val rule = post(client, root + RULE_API, csrf, referer, """{"semesterId":"$semesterId"}""")
                    bandHoursOf(rule)
                } catch (e: JwException) {
                    CipBandHours.DEFAULT
                } catch (e: java.io.IOException) {
                    CipBandHours.DEFAULT
                }

            // ③ 学期元数据：周历给总周数与第 1 周周一，基础信息给学期名；同会话内尽力而为
            val meta = metaOf(client, root, csrf, referer, semesterId)
            val maskWeeks = meta?.totalWeeks?.takeIf { it > 0 } ?: maxOf(totalWeeks, MIN_TOTAL_WEEKS)

            // ④ 课表数据：周次一次带全，条目自带 weekNos；掩码长度与学校学期一致
            val weeks = (1..maxOf(maskWeeks, MIN_TOTAL_WEEKS)).joinToString(",")
            val body =
                post(
                    client,
                    root + TIMETABLE_API,
                    csrf,
                    referer,
                    """{"semesterId":"$semesterId","weekNo":"$weeks"}""",
                )
            val drafts = parseTimetable(body, hours, maskWeeks)
            if (drafts.isEmpty()) {
                throw JwException.ParseFailed("未解析到课程：请确认该账号本学期有排课")
            }

            // ⑤ 作息：教务配了用教务的，没配用本校实测值
            val times = sectionTimesOf(client, root, csrf, referer, semesterId, hours)
            JwFetchResult(drafts, times, meta)
        }

    /** 教务的钟点配置（按校区分组）；为空或认不出来时回落到本校实测作息 */
    internal fun sectionTimesOf(
        client: OkHttpClient,
        root: String,
        csrf: String,
        referer: String,
        semesterId: String,
        hours: CipBandHours,
    ): List<SectionTimeDraft> =
        try {
            val body =
                post(client, root + RULE_TIME_API, csrf, referer, """{"semesterId":"$semesterId"}""")
            parseRuleTimes(body, hours).ifEmpty { SECTION_TIMES }
        } catch (e: JwException) {
            SECTION_TIMES
        } catch (e: java.io.IOException) {
            SECTION_TIMES
        }

    /**
     * 学期元数据：周历（总周数 + 第 1 周周一）与学期名，两路都失败就返回 null
     * （本地值原样保留）。任一路成功都算有收获——字段各自独立覆盖。
     */
    internal fun metaOf(
        client: OkHttpClient,
        root: String,
        csrf: String,
        referer: String,
        semesterId: String,
    ): JwMeta? {
        val weeks =
            try {
                val body =
                    post(client, root + WEEK_LIST_API, csrf, referer, """{"semesterId":"$semesterId"}""")
                parseWeekList(body)
            } catch (e: JwException) {
                null
            } catch (e: java.io.IOException) {
                null
            }
        val semester =
            try {
                val body =
                    post(client, root + SEMESTER_API, csrf, referer, """{"dataTypes":"semester"}""")
                parseSemesterName(body, semesterId)
            } catch (e: JwException) {
                ""
            } catch (e: java.io.IOException) {
                ""
            }
        if (weeks == null && semester.isBlank()) return null
        return JwMeta(
            semester = semester,
            startDate = weeks?.second.orEmpty(),
            totalWeeks = weeks?.first ?: 0,
        )
    }

    /**
     * 周历 → (总周数, 第 1 周周一)。条目按 weekNo 排序后取首条起点；
     * 总周数取最大 weekNo 而不是数组长度——缺项的周历不会把后面的周数吃掉。
     */
    internal fun parseWeekList(body: String): Pair<Int, String>? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonArray ?: return null
        val weeks =
            data.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val no = obj.int("weekNo") ?: return@mapNotNull null
                val start = obj.str("startDate").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                no to start
            }
        if (weeks.isEmpty()) return null
        val sorted = weeks.sortedBy { it.first }
        return sorted.last().first to sorted.first().second
    }

    /** 学期列表 → 当前学期的名字；找不到匹配 id 时返回空串（不猜） */
    internal fun parseSemesterName(
        body: String,
        semesterId: String,
    ): String {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        val data = root["data"] as? JsonObject ?: return ""
        val list = data["semesterList"] as? JsonArray ?: return ""
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            if (obj.str("semesterId") == semesterId) return obj.str("semesterName").trim()
        }
        return ""
    }

    /** 课表页 → (CSRF 令牌, 当前学期 ID)；两样都齐才算认出来了 */
    internal fun parseTimetablePage(html: String): Pair<String, String>? {
        val csrf = CSRF_META.find(html)?.groupValues?.get(1) ?: return null
        val semester = SEMESTER_ID.find(html)?.groupValues?.get(1) ?: return null
        if (csrf.isBlank() || semester.isBlank()) return null
        return csrf to semester
    }

    /**
     * 课表 JSON → 课程。`studentTimetableList[时段]["星期_节段"]` 下的每条都自带
     * `weekNos`（如 "6,7,8"），直接建周次掩码；周次缺了才退回 `startEndWeek`，再退全学期。
     */
    internal fun parseTimetable(
        body: String,
        hours: CipBandHours,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        val bands = data["studentTimetableList"] as? JsonObject ?: return emptyList()

        val drafts = mutableListOf<CourseDraft>()
        for ((timeType, slots) in bands) {
            val slotMap = slots as? JsonObject ?: continue
            for ((key, entries) in slotMap) {
                val match = SLOT_KEY.find(key) ?: continue
                val weekDay = match.groupValues[1].toIntOrNull() ?: continue
                val s1 = match.groupValues[2].toIntOrNull() ?: continue
                val s2 = match.groupValues[3].toIntOrNull() ?: continue
                val array = entries as? JsonArray ?: continue
                for (element in array) {
                    draftOf(element, timeType, weekDay, s1, s2, hours, totalWeeks)?.let { drafts += it }
                }
            }
        }
        // 合并班的同一格可能给出多条相同条目，去重后交给统一的导入 Diff
        return drafts
            .distinctBy {
                listOf(
                    it.name,
                    it.teacher,
                    it.room,
                    it.dayOfWeek,
                    it.startSection,
                    it.endSection,
                    it.weekMask,
                )
            }.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.trim().toIntOrNull()
    }

    private fun draftOf(
        element: JsonElement?,
        timeType: String,
        keyWeekDay: Int,
        s1: Int,
        s2: Int,
        hours: CipBandHours,
        totalWeeks: Int,
    ): CourseDraft? {
        val obj = element as? JsonObject ?: return null
        val name = obj.str("courseName").trim()
        if (name.isEmpty()) return null
        val weekDay = (obj.int("weekDay") ?: keyWeekDay).coerceIn(MIN_DAY, MAX_DAY)
        val offset = hours.offsetOf(timeType)
        val start = offset + s1
        val end = offset + s2
        if (start > SectionTimes.MAX_SECTIONS || end < start) return null
        val startSection = start.coerceIn(1, SectionTimes.MAX_SECTIONS)
        val endSection = end.coerceIn(startSection, SectionTimes.MAX_SECTIONS)
        val room = obj.str("classroomNo").ifBlank { obj.str("classroomName") }.trim()
        return CourseDraft(
            name = name,
            teacher = obj.str("mainTeacherName").trim(),
            room = room,
            dayOfWeek = weekDay,
            startSection = startSection,
            endSection = endSection,
            weekMask = weekMaskOf(obj, totalWeeks),
        )
    }

    /** `weekNos` 是逗号分隔的周次列表（"6,7,8"）；缺了退回 `startEndWeek`，都没有按整学期 */
    private fun weekMaskOf(
        obj: JsonObject,
        totalWeeks: Int,
    ): String {
        val weeks = obj.str("weekNos").ifBlank { obj.str("startEndWeek") }
        val parsed =
            weeks
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..totalWeeks }
        return if (parsed.isEmpty()) WeekMask.of(totalWeeks) else WeekMask.fromWeeks(totalWeeks, parsed)
    }

    /**
     * 排课规则 → 各时段节数。空缺按 0 计（与前端 `toHourNumber` 一致），
     * 字段认不出来时整体退回 [CipBandHours.DEFAULT]——偏移错一节，全表全错。
     */
    internal fun bandHoursOf(body: String): CipBandHours {
        val root =
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return CipBandHours.DEFAULT
        val data = root["data"] as? JsonObject ?: return CipBandHours.DEFAULT
        val morning = data.int("morningHour") ?: return CipBandHours.DEFAULT
        val am = data.int("amHour") ?: return CipBandHours.DEFAULT
        val noon = data.int("noonHour") ?: return CipBandHours.DEFAULT
        val pm = data.int("pmHour") ?: return CipBandHours.DEFAULT
        val night = data.int("nightHour") ?: return CipBandHours.DEFAULT
        val evening = data.int("eveningHour") ?: return CipBandHours.DEFAULT
        return CipBandHours(morning, am, noon, pm, night, evening)
    }

    /**
     * 教务钟点配置 → 作息。形状（前端 `getClassTime` 的取法）：
     * `data[校区ID][时段]["classIndex3"] = ["10:00", "10:45"]`，[0] 是上课、[1] 是下课。
     * 配置按校区分组，多校区取并集，序号相同以后出现的为准。
     */
    internal fun parseRuleTimes(
        body: String,
        hours: CipBandHours = CipBandHours.DEFAULT,
    ): List<SectionTimeDraft> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        val byNo = mutableMapOf<Int, Pair<String, String>>()
        for (campus in data.values) {
            val campusObj = campus as? JsonObject ?: continue
            for ((timeType, slots) in campusObj) {
                val slotObj = slots as? JsonObject ?: continue
                val offset = hours.offsetOf(timeType)
                for ((field, times) in slotObj) {
                    val no = field.removePrefix("classIndex").toIntOrNull() ?: continue
                    val array = times as? JsonArray ?: continue
                    val values = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val start = values.firstOrNull() ?: continue
                    val end = values.lastOrNull() ?: continue
                    if (start.isBlank() || end.isBlank()) continue
                    // 配置按时段内序号存，换算成全天节次
                    val dayNo = offset + no
                    if (dayNo in 1..SectionTimes.MAX_SECTIONS) byNo[dayNo] = start to end
                }
            }
        }
        return byNo
            .map { (no, times) -> SectionTimeDraft(no, times.first, times.second) }
            .sortedBy { it.sectionNo }
    }

    /** 门户根：丢掉 path / query / fragment，抓取从根路径出发 */
    internal fun portalRoot(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        return parsed
            .newBuilder()
            .encodedPath("/")
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

    /** CIP 的 POST 一律 JSON 体 + CSRF 头；缺头或会话失效时服务端回 403 */
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
}

/**
 * CIP 的时段节数（`getTimetableRule`）：一天由 早读/上午/中午/下午/晚上/晚间 六段组成，
 * 槽位里的序号是「时段内 1 起」，换算全天节次时按前序时段的节数累加偏移——
 * 与前端 `getTimeTypeStartIndex` 的口径一致。
 */
data class CipBandHours(
    val morning: Int,
    val am: Int,
    val noon: Int,
    val pm: Int,
    val night: Int,
    val evening: Int,
) {
    /** [timeType] 时段第一小节的全天序号减一（0 起）；认不出的时段按上午之后接续不了，归 0 */
    fun offsetOf(timeType: String): Int =
        when (timeType) {
            "morning" -> 0
            "am" -> morning
            "noon" -> morning + am
            "pm" -> morning + am + noon
            "night" -> morning + am + noon + pm
            "evening" -> morning + am + noon + pm + night
            else -> 0
        }

    companion object {
        /** 广西工程职业学院 2026 秋实测：上午 4 / 下午 4 / 晚上 4，其余为 0 */
        val DEFAULT = CipBandHours(morning = 0, am = 4, noon = 0, pm = 4, night = 4, evening = 0)
    }
}
