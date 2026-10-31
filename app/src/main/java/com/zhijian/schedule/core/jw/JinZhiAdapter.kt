package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 金智教务适配器（wisedu EAMS，`/eams/` 下 Struts 风格 action）。
 *
 * 与树维经典 EAMS 同住 `/eams/`，但学生课表入口不同——树维是
 * `courseTableForStd`（会话内默认学生），金智是 `courseTableStudent`
 * （按学号检索后进课表），页面内嵌 `TaskActivity` JS 数组渲染课表，
 * HTML 表格是空壳。识别靠 `courseTableStudent` / `teach/grade` 端点指纹，
 * 裸 `/eams/` 仍判树维（注册表里树维在前）。
 *
 * 抓取：优先复用用户停留的课表页地址（菜单里「我的课表」链接自带
 * semesterId/stdId 参数）；否则直接重放无参的 courseTable 入口
 * （多数部署默认当前学期本人）。
 */
object JinZhiAdapter : JwAdapter {
    override val id = "jinzhi"
    override val name = "金智教务"

    override val steps =
        AdapterSteps(
            timetablePage = "我的课表",
            note = "金智教务在左侧菜单「我的课表」；打开后地址栏带 courseTableStudent 才算进对页",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val EAMS = "/eams/"
    private const val TIMETABLE_STUDENT = "courseTableStudent!courseTable.action"
    private const val TIMETABLE_STD = "courseTableForStd!courseTable.action"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("coursetablestudent") -> JwMatchScore.EXACT
            path.contains("teach/grade") -> JwMatchScore.ENDPOINT
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
            val referer = base
            val candidates =
                buildList {
                val current = parseUrl(baseUrl)
                if (current != null && current.encodedPath.contains("oursetable")) {
                    add(baseUrl) // 用户已停在带参数的课表页，原样重放
                }
                add(base + TIMETABLE_STUDENT)
                add(base + TIMETABLE_STD)
            }

            for (url in candidates) {
                val html =
                    try {
                        get(client, url, referer)
                    } catch (ignored: IOException) {
                        continue
                    }
                if (isLoginPage(html)) throw JwException.NeedsLogin()
                val drafts = parsePage(html, totalWeeks)
                if (drafts.isNotEmpty()) {
                    return@withContext JwFetchResult(drafts, SectionTimes.fromHtml(html))
                }
            }
            throw JwException.ParseFailed(
                "金智教务未返回课表：请先在网页中进入「我的课表」页再导入",
            )
        }

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：金智的成绩入口命名按部署不一（historyCourseGrade /
     * search / stdExamScore），候选逐个 GET，通用表头解析；全不中返回空。
     */
    override suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> {
        val base = JwUrlGuard.safeHttpUrl(baseUrl)?.let { normalizeBase(it.toString()) } ?: return emptyList()
        return GradeParser.fetchViaCandidates(
            client = client,
            candidates =
                listOf(
                    base + "teach/grade/course/person!historyCourseGrade.action",
                    base + "teach/grade/course/person!search.action",
                    base + "stdExamScore.action",
                ),
            referer = base,
            userAgent = UA,
            isDeadPage = ::isLoginPage,
        )
    }

    /** 页面课表：TaskActivity JS 数组优先（HTML 表格是空壳），服务端渲染的表格兜底 */
    internal fun parsePage(
        html: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        val tasks = EamsTaskParser.parse(html, totalWeeks)
        if (tasks.isNotEmpty()) return tasks
        return HtmlTableScheduleParser.parse(html, totalWeeks)
    }

    /**
     * semesterCalendar → 当前学期名。响应是内嵌 JS：`semesters: {...}`
     * 按学年分组的数组（`schoolYear` + `name`）。取「离今天最近的一档」没有
     * 现成字段，这里只解析出学年学期文本列表供测试与元数据兜底使用。
     */
    internal fun parseSemesterNames(body: String): List<String> {
        val years = Regex("""schoolYear\s*:\s*"(\d{4}-\d{4})"""").findAll(body).map { it.groupValues[1] }.toList()
        val names = Regex("""name\s*:\s*"([12])"""").findAll(body).map { it.groupValues[1] }.toList()
        return years.zip(names) { y, n -> "$y-$n" }.distinct()
    }

    /** `/eams/` 基路径；地址里没有时退回目录标注，再不行按主机根处理 */
    internal fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val eams = path.indexOf(EAMS)
        val basePath =
            when {
                eams >= 0 -> path.substring(0, eams + EAMS.length)
                path.endsWith("/eams") -> "$path/"
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

    /** 金智登录页（校级 CAS 或 eams 自带登录表单） */
    private fun isLoginPage(html: String): Boolean =
        html.contains("type=\"password\"", ignoreCase = true) &&
            (html.contains("登录") || html.contains("login", ignoreCase = true))

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
}
