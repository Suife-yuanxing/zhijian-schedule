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
 * 树维（Supwisdom）经典 EAMS 教务适配器。
 *
 * 识别锚点是 `/eams/` 路径与 `courseTableForStd`——注意与树维 eams5
 * （`/eams5-student/`，三段式 JSON）区分，二者不是同一套接口。
 *
 * 课表页 `coursetable/courseTableForStd!courseTable.action` 由服务端渲染成
 * 「节次 × 星期」大表，行首是节次序号、单元格内以 `div.course-name /
 * course-teacher / course-room` 分层，因此解析统一交给
 * [HtmlTableScheduleParser]（已支持纯数字行标签与 div 拼行）。
 *
 * 登录由 WebView 完成；本适配器只带共享 Cookie 重放，不做静默重登——
 * 树维的登录态由 CAS 票据维护，重放登录表单会污染用户会话。
 */
object ShuWeiClassicAdapter : JwAdapter {
    override val id = "shuwei_classic"
    override val name = "树维 EAMS"

    override val steps =
        AdapterSteps(
            timetablePage = "我的课表",
            note = "课表页地址里带 courseTableForStd 才算进对页",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val EAMS = "/eams/"

    /** 课表页参数：`ignoreHead` 去掉表头导航、`isEng` 强制中文 */
    private val TIMETABLE_PATHS =
        listOf(
            "coursetable/courseTableForStd!courseTable.action?ignoreHead=1&isEng=false",
            "coursetable/courseTableForStd!courseTable.action?ignoreHead=1",
            "coursetable/courseTableForStd!courseTable.action",
            "coursetable/std/courseTableForStd!courseTable.action",
        )

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains("coursetableforstd") -> JwMatchScore.EXACT
            path.contains("/eams5-student/") || path.contains("/eams5/") -> JwMatchScore.NONE
            path.contains(EAMS) || path.endsWith("/eams") -> JwMatchScore.STRONG
            path.contains("loginext.action") || path.contains("locallogin.action") -> JwMatchScore.ENDPOINT
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
            val base = normalizeBase(baseUrl)
            val referer = "${base}coursetable/courseTableForStd!index.action"
            var lastError: String? = null
            for (path in TIMETABLE_PATHS) {
                val url = base + path
                val html =
                    try {
                        get(client, url, referer)
                    } catch (e: JwException) {
                        throw e
                    } catch (e: IOException) {
                        lastError = e.message
                        continue
                    }
                if (isLoginPage(html)) throw JwException.NeedsLogin()
                val drafts = HtmlTableScheduleParser.parse(html, totalWeeks)
                if (drafts.isNotEmpty()) return@withContext drafts
                lastError = "未找到课表表格：$url"
            }
            throw JwException.ParseFailed(
                "树维未返回课表：${lastError ?: "请先在网页中进入「我的课表」再导入"}",
            )
        }

    private fun isLoginPage(html: String): Boolean =
        isSessionDeadPage(html) ||
            (html.contains("login.action") && !html.contains("courseTable"))

    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val index = path.indexOf(EAMS)
        val basePath =
            when {
                index >= 0 -> path.substring(0, index + EAMS.length)
                path.endsWith("/eams") -> "$path/"
                else -> SchoolCatalog.knownBasePath(parsed.host) ?: EAMS
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
