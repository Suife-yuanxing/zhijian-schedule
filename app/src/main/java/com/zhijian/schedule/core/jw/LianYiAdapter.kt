package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 联奕教务适配器（`lyuapServer` 统一身份认证 + React 课表页）。
 *
 * 登录走联奕统一认证（`/lyuapServer/login`，CAS 票据链），由 WebView 完成；
 * 课表页是 React 应用，容器类名 `app-components-CourseTimeTable-styles-timeTable`。
 * 部分部署服务端渲染出表格 DOM（可解析），更多是客户端渲染——OkHttp 拿到的
 * 只有 JS 壳。因此这里只做「页面里真有课表 DOM 就解析」的尽力而为：
 *
 * - 命中：解析用户停留页里的课表表格（通用表头识别）；
 * - 未命中：给出明确提示（这类学校建议用截图导入），
 *   绝不静默回退到其他厂商解析器。
 *
 * 成绩端点未公开文档化，暂不支持。
 */
object LianYiAdapter : JwAdapter {
    override val id = "lianyi"
    override val name = "联奕教务"

    override val steps =
        AdapterSteps(
            timetablePage = "课表页面",
            note = "联奕教务是网页应用：请停留在课表页（能看到课程格子）再点抓取；若提示无数据，请用截图导入",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val TIMETABLE_MARK = "CourseTimeTable"

    /** 与 lowercased path 比对，小写形态 */
    private const val CAS_MARK = "lyuapserver"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains(CAS_MARK) -> JwMatchScore.EXACT
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
            // React 应用：只有用户实际停留的课表页可能（服务端渲染时）带出 DOM，
            // 候选路径猜不出路由，因此只抓当前地址本身。
            val html =
                try {
                    get(client, baseUrl)
                } catch (ignored: IOException) {
                    throw JwException.NetworkFailed("教务连接失败")
                }
            if (isLoginPage(html)) throw JwException.NeedsLogin()
            if (!html.contains(TIMETABLE_MARK, ignoreCase = true)) {
                throw JwException.ParseFailed(
                    "联奕教务的课表是网页应用渲染的，这里抓不到数据；请在课表页重试，或改用截图导入",
                )
            }
            val drafts = HtmlTableScheduleParser.parse(html, totalWeeks)
            if (drafts.isEmpty()) {
                throw JwException.ParseFailed(
                    "课表页没有可识别的课程表格；请用截图导入",
                )
            }
            JwFetchResult(drafts, SectionTimes.fromHtml(html))
        }

    /** 联奕 CAS 登录页 / 任何带密码表单的页面 */
    private fun isLoginPage(html: String): Boolean {
        val casLogin =
            html.contains(CAS_MARK, ignoreCase = true) &&
                (html.contains("登录") || html.contains("login", ignoreCase = true))
        val passwordForm = html.contains("type=\"password\"", ignoreCase = true) && html.contains("登录")
        return casLogin || passwordForm
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
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}
