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
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * 乘方教务适配器（登录页页脚「乘方教务」，两代接口都在内）。
 *
 * - **新版**（`/new/` 目录）：`GET /new/student/xsgrkb/week.page` 拿学期下拉
 *   `#xnxqdm` 与 `var businessHours` 作息，`POST getCalendarWeekDatas`
 *   拿整学期课程 JSON（xq / ps / pe / zc / kcmc / teaxms / jcxdmc2）；
 * - **旧版**（Struts2 `.action`）：`GET xsgrkbcx!getXsgrbkList.action` 同样带
 *   `#xnxqdm`，`GET xsgrkbcx!xsAllKbList.action?xnxqdm=` 返回内嵌
 *   `var kbxx = [...]`（kcmc / teaxms / jcxdmc / xq / jcdm2 / zcs）。
 *
 * 登录由 WebView 完成；两代课表页都按学期下拉的选中项取当前学期。
 * 成绩端点两代都未公开文档化，暂不支持（gradeFetchSupported=false）。
 */
object ChengFangAdapter : JwAdapter {
    override val id = "chengfang"
    override val name = "乘方教务"

    override val steps =
        AdapterSteps(
            timetablePage = "学生课表",
            note = "登录后进入「学生课表」即可，无需选择学期",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val NEW_PAGE = "new/student/xsgrkb/week.page"
    private const val NEW_COURSE_API = "new/student/xsgrkb/getCalendarWeekDatas"
    private const val OLD_PAGE = "xsgrkbcx!getXsgrbkList.action"
    private const val OLD_COURSE_API = "xsgrkbcx!xsAllKbList.action"

    private const val CODE_OK = 0
    private const val TERM_LENGTH = 4
    private const val TIME_LENGTH = 5
    private const val MIN_DAY = 1
    private const val MAX_DAY = 7

    private val BUSINESS_HOURS = Regex("""var\s+businessHours\s*=\s*\$\.parseJSON\('(\[.*?\])'\);""")
    private val KBXX = Regex("""var\s+kbxx\s*=\s*(\[[\s\S]*?\]);""")

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("/new/student/") -> JwMatchScore.EXACT
            path.contains("xsgrkbcx") || path.contains("week.page") -> JwMatchScore.EXACT
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
            val base = normalizeBase(baseUrl)
            // 两代课表页同构（#xnxqdm 学期下拉），先试新版再试旧版
            listOf(NEW_PAGE, OLD_PAGE).firstNotNullOfOrNull { page ->
                tryPage(client, base, page, totalWeeks)
            } ?: throw JwException.ParseFailed("乘方教务未返回课表：请确认已登录并进入「学生课表」页")
        }

    /** 单个课表页尝试：网络失败 / 未登录到课表 / 无数据都返回 null 继续下一候选 */
    private fun tryPage(
        client: OkHttpClient,
        base: String,
        page: String,
        totalWeeks: Int,
    ): JwFetchResult? {
        val html = runCatching { get(client, base + page, base) }.getOrNull() ?: return null
        if (isLoginPage(html)) throw JwException.NeedsLogin()
        val semester = selectedSemester(html) ?: return null
        val isNew = page == NEW_PAGE
        val drafts =
            if (isNew) {
                newGenCourses(client, base, semester, totalWeeks)
            } else {
                oldGenCourses(client, base, semester, totalWeeks)
            }
        val times = if (isNew) businessHoursOf(html) else emptyList()
        return drafts
            .takeIf { it.isNotEmpty() }
            ?.let { JwFetchResult(it, times, JwMeta(semester = semester.label)) }
    }

    // ---------- 学期下拉（两代同构） ----------

    internal data class Semester(
        val value: String,
        val label: String,
    )

    /** `#xnxqdm` 下拉的选中项；没有 selected 时取第一项 */
    internal fun selectedSemester(html: String): Semester? {
        val doc = Jsoup.parse(html)
        val select = doc.getElementById("xnxqdm") ?: return null
        val options = select.select("option[value]")
        val picked = options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull() ?: return null
        return Semester(picked.attr("value").trim(), picked.text().trim())
    }

    // ---------- 新版：整学期 JSON ----------

    private fun newGenCourses(
        client: OkHttpClient,
        base: String,
        semester: Semester,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val year = semester.value.take(TERM_LENGTH).toIntOrNull() ?: return emptyList()
        val form =
            FormBody
                .Builder()
                .add("xnxqdm", semester.value)
                .add("zc", "")
                .add("d1", "$year-08-01 00:00:00")
                .add("d2", "${year + 1}-08-31 23:59:59")
                .build()
        val body =
            try {
                post(client, base + NEW_COURSE_API, base, form)
            } catch (ignored: IOException) {
                return emptyList()
            } catch (ignored: JwException.NetworkFailed) {
                return emptyList()
            }
        return parseNewGenBody(body, totalWeeks)
    }

    /** 新版课程 JSON → 课表；code!=0 或结构不认识返回空 */
    internal fun parseNewGenBody(
        body: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root.int("code") != CODE_OK) return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data
            .mapNotNull { it as? JsonObject }
            .flatMap { newGenDraft(it, totalWeeks) }
            .merge()
    }

    /** 一条记录 → 若按教室分周（jcxdmc2）则拆成多条 */
    private fun newGenDraft(
        item: JsonObject,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val name = item.str("kcmc").trim()
        val day = item.int("xq")
        val start = item.int("ps")
        val end = item.int("pe")
        val weeks = expandWeeks(item.str("zc"))
        val valid =
            name.isNotEmpty() &&
                day != null &&
                day in MIN_DAY..MAX_DAY &&
                start != null &&
                end != null &&
                start in 1..end &&
                end <= SectionTimes.MAX_SECTIONS &&
                weeks.isNotEmpty()
        if (!valid) return emptyList()

        val teacher = cleanTeacher(item.str("teaxms"))
        val venues = parseVenueWeeks(item.str("jcxdmc2"))
        if (venues.isEmpty()) {
            val room =
                when {
                    item.str("jcxdmc").isNotBlank() -> item.str("jcxdmc").trim()
                    else -> ""
                }
            return listOf(draft(name, teacher, room, day, start..end, weeks, totalWeeks))
        }
        return venues.map { (room, venueWeeks) ->
            draft(name, teacher, room, day, start..end, venueWeeks, totalWeeks)
        }
    }

    // ---------- 旧版：内嵌 kbxx JSON ----------

    private fun oldGenCourses(
        client: OkHttpClient,
        base: String,
        semester: Semester,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val url =
            (base + OLD_COURSE_API).toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("xnxqdm", semester.value)
                ?.build()
                .toString()
        val body =
            try {
                get(client, url, base)
            } catch (ignored: IOException) {
                return emptyList()
            }
        return parseOldGenBody(body, totalWeeks)
    }

    /** 旧版响应：内嵌 `var kbxx = [...]` 的 HTML → 课表 */
    internal fun parseOldGenBody(
        body: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val json = KBXX.find(body)?.groupValues?.get(1) ?: return emptyList()
        // JS 字符串里的「教A-201\206」这类反斜杠合并写法不是合法 JSON 转义，
        // 先把无效转义补成字面反斜杠再解析（用 lambda 版本——字符串替换版
        // 的 \ 会被 Java 语义二次解释，等于没换）
        val safe = INVALID_ESCAPE.replace(json) { "\\\\" }
        val root = runCatching { Json.parseToJsonElement(safe) as? JsonArray }.getOrNull() ?: return emptyList()
        return root
            .mapNotNull { item -> oldGenDraft(item as? JsonObject, totalWeeks) }
            .merge()
    }

    private fun oldGenDraft(
        item: JsonObject?,
        totalWeeks: Int,
    ): CourseDraft? {
        val name = item?.str("kcmc").orEmpty().trim()
        val day = item?.int("xq")
        val sections = expandWeeks(item?.str("jcdm2").orEmpty())
        val weeks = expandWeeks(item?.str("zcs").orEmpty())
        val start = sections.firstOrNull()
        val end = sections.lastOrNull()
        val valid =
            item != null &&
                name.isNotEmpty() &&
                day != null &&
                day in MIN_DAY..MAX_DAY &&
                start != null &&
                end != null &&
                end <= SectionTimes.MAX_SECTIONS &&
                weeks.isNotEmpty()
        if (!valid) return null
        val room = item.str("jcxdmc").ifEmpty { item.str("jcxdmcs") }.replace('\\', '/').trim()
        return draft(name, cleanTeacher(item.str("teaxms")), room, day, start..end, weeks, totalWeeks)
    }

    // ---------- 作息（新版 week.page 内嵌） ----------

    /** `var businessHours = $.parseJSON('[{jcdm,qssj,jssj},…]')` → 小节作息 */
    internal fun businessHoursOf(html: String): List<SectionTimeDraft> {
        val json = BUSINESS_HOURS.find(html)?.groupValues?.get(1) ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
        return root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val no = item.int("jcdm") ?: return@mapNotNull null
            val start = item.str("qssj").trim().take(TIME_LENGTH)
            val end = item.str("jssj").trim().take(TIME_LENGTH)
            val shapeOk = no in 1..SectionTimes.MAX_SECTIONS && start.contains(':') && end.contains(':')
            if (!shapeOk) return@mapNotNull null
            SectionTimeDraft(no, start, end)
        }
    }

    /** 乘方登录页：密码表单 + 登录按钮；课表页不出现 */
    private fun isLoginPage(html: String): Boolean =
        html.contains("type=\"password\"", ignoreCase = true) && html.contains("登录")

    /** 基路径：地址带 `/new/` 或 `xsgrkbcx` / `week.page` 时截到所在目录 */
    internal fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val marker =
            listOf("/new/", "xsgrkbcx", "week.page").firstNotNullOfOrNull { token ->
                path.indexOf(token, ignoreCase = true).takeIf { it >= 0 }?.let { it to token }
            }
        val basePath =
            when {
                marker != null -> path.substring(0, marker.first + marker.second.length).substringBeforeLast("/") + "/"
                path.endsWith("/") -> path
                else -> SchoolCatalog.knownBasePath(parsed.host) ?: "/"
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
        form: FormBody,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", JSON_MEDIA)
                .post(form)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }

    private const val JSON_MEDIA = "application/x-www-form-urlencoded; charset=UTF-8"

    /** JS 内嵌 JSON 里的无效转义（如房间名「教A-201\206」） */
    private val INVALID_ESCAPE = Regex("""\\(?!["\\/bfnrtu])""")
}

private val WEEK_RANGE = Regex("""(\d+)\s*[-~]\s*(\d+)""")

/** jcxdmc2 = 「教室名-周次,教室名-周次」，反斜杠表示连堂教室（B205\206） */
private fun parseVenueWeeks(raw: String): List<Pair<String, List<Int>>> {
    val byRoom = LinkedHashMap<String, MutableList<Int>>()
    raw.split(",").forEach { part ->
        val text = part.trim()
        val dash = text.lastIndexOf('-')
        if (dash <= 0) return@forEach
        val room = text.substring(0, dash).replace('\\', '/').trim()
        val week = text.substring(dash + 1).trim().toIntOrNull() ?: return@forEach
        if (week > 0 && room.isNotEmpty()) byRoom.getOrPut(room) { mutableListOf() }.add(week)
    }
    return byRoom.map { (room, weeks) -> room to weeks.distinct().sorted() }
}

private fun draft(
    name: String,
    teacher: String,
    room: String,
    day: Int,
    sections: IntRange,
    weeks: List<Int>,
    totalWeeks: Int,
): CourseDraft =
    CourseDraft(
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = day,
        startSection = sections.first,
        endSection = sections.last,
        weekMask = WeekMask.fromWeeks(totalWeeks, weeks),
    )

/** 同名同教室同时段的周次并集（乘方按教学任务拆行，同课会出现多条） */
private fun List<CourseDraft>.merge(): List<CourseDraft> {
    val merged = LinkedHashMap<String, CourseDraft>()
    forEach { draft ->
        val key =
            listOf(draft.name, draft.teacher, draft.room, draft.dayOfWeek, draft.startSection, draft.endSection)
                .joinToString("|")
        val old = merged[key]
        merged[key] =
            if (old == null) {
                draft
            } else {
                val weeks = WeekMask.weeks(old.weekMask) + WeekMask.weeks(draft.weekMask)
                draft.copy(weekMask = WeekMask.fromWeeks(draft.weekMask.length, weeks))
            }
    }
    return merged.values.toList()
}

/** 周次/节次串：逗号分隔 + 「1-4」区间写法 */
private fun expandWeeks(raw: String): List<Int> {
    val out = mutableListOf<Int>()
    raw.split(",").forEach { part ->
        val text = part.trim().removeSuffix("周")
        val range = WEEK_RANGE.find(text)
        if (range != null) {
            val a = range.groupValues[1].toIntOrNull()
            val b = range.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..b) out += a..b
        } else {
            text.toIntOrNull()?.takeIf { it > 0 }?.let { out += it }
        }
    }
    return out.distinct().sorted()
}

/** 教师「张三,李四[讲师]」→ 去括注去重 */
private fun cleanTeacher(raw: String): String =
    raw
        .split(",", "，")
        .map { it.trim().replace(Regex("""\[[^\]]*]"""), "").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? {
    val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
    return raw.trim().toIntOrNull()
}
