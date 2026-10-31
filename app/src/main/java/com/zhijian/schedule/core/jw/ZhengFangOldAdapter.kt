package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.Charset

/**
 * 正方旧版教务适配器（ASP.NET 页面：`default2.aspx` 登录、`xs_main.aspx` 主页）。
 *
 * 新版（zf_new / jwglxt）早已另立适配器；老 ASP 部署在高职与民办院校仍有存量。
 * 页面编码 GBK（响应头常不带 charset，按 UTF-8 解会变乱码），全程显式按 GBK 解码。
 *
 * 老正方的会话既靠 Cookie 也靠 URL 上的 `?xh=学号`：登录后跳
 * `xs_main.aspx?xh=xxx`，课表/成绩页都要带上它。抓取优先从用户停留页的
 * 地址里取 `xh`；拿不到就试无参地址（部分部署只认 Cookie）。
 *
 * 课表页 `xskbcx.aspx` 是服务端渲染的「星期 × 节次」大表，交给
 * [HtmlTableScheduleParser]；成绩页候选 `xscjcx.aspx` / `xscj_gc.aspx`
 * 交给 [GradeParser.parseGeneric] 通用表头解析。
 *
 * 撞名说明：青果也用 `xskbcx.aspx` 命名且指纹更具体（带 `.aspx` 全名 100 分），
 * 用户直接停在同名课表页时会判给青果；老正方的识别锚点是
 * `default2.aspx` / `xs_main.aspx`（登录与主页，EXACT）。
 */
object ZhengFangOldAdapter : JwAdapter {
    override val id = "zhengfang_old"
    override val name = "正方教务（旧版）"

    override val steps =
        AdapterSteps(
            timetablePage = "信息查询 → 学生课表",
            note = "老版正方在左侧菜单「信息查询」里；登录后地址栏会带 xh= 学号",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private val CHARSET: Charset = charset("GBK")

    private const val TIMETABLE_PAGE = "xskbcx.aspx"
    private const val MAIN_PAGE = "xs_main.aspx"
    private const val LOGIN_PAGE = "default2.aspx"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        val path = parsed.encodedPath.lowercase()
        return when {
            path.contains(LOGIN_PAGE) || path.contains(MAIN_PAGE) -> JwMatchScore.EXACT
            path.contains("xskbcx") || path.contains("xscjcx") -> JwMatchScore.ENDPOINT
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
            val referer = base + MAIN_PAGE
            val xh = xhOf(baseUrl)
            val candidates =
                if (xh.isNullOrBlank()) {
                    listOf(base + TIMETABLE_PAGE)
                } else {
                    listOf(
                        base + TIMETABLE_PAGE + "?xh=" + xh,
                        base + TIMETABLE_PAGE,
                    )
                }

            for (url in candidates) {
                val html =
                    try {
                        get(client, url, referer)
                    } catch (ignored: IOException) {
                        continue
                    }
                if (isLoginPage(html)) throw JwException.NeedsLogin()
                val drafts = HtmlTableScheduleParser.parse(html, totalWeeks)
                if (drafts.isNotEmpty()) {
                    return@withContext JwFetchResult(drafts, SectionTimes.fromHtml(html))
                }
            }
            throw JwException.ParseFailed(
                "老版正方未返回课表：请先登录并进入「学生课表」页（地址栏应带 xh= 学号）",
            )
        }

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：老正方的成绩页按部署有 `xscjcx.aspx` / `xscj_gc.aspx`
     * 两代命名，候选逐个 GET（GBK 解码），通用表头解析；全不中返回空。
     */
    override suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> {
        val base = JwUrlGuard.safeHttpUrl(baseUrl)?.let { normalizeBase(it.toString()) } ?: return emptyList()
        val xh = xhOf(baseUrl)
        val suffix = if (xh.isNullOrBlank()) "" else "?xh=" + xh
        return GradeParser.fetchViaCandidates(
            client = client,
            candidates =
                listOf(
                    base + "xscjcx.aspx" + suffix,
                    base + "xscj_gc.aspx" + suffix,
                    base + "Xscjcx.aspx" + suffix,
                ),
            referer = base + MAIN_PAGE,
            userAgent = UA,
            isDeadPage = ::isLoginPage,
            charset = CHARSET,
        )
    }

    /** 老正方地址里的学号：登录后落在 `xs_main.aspx?xh=xxx` */
    internal fun xhOf(url: String): String? = parseUrl(url)?.queryParameter("xh")

    /** 老正方部署在主机根或某个子目录；地址含登录/主页文件名时截到所在目录 */
    internal fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val marker =
            listOf("/" + MAIN_PAGE, "/" + LOGIN_PAGE, "/" + TIMETABLE_PAGE)
                .firstNotNullOfOrNull { token ->
                    path.lastIndexOf(token).takeIf { it >= 0 }?.let { it to token }
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

    /**
     * 登录页是带 `TextBox1`（学号）与 `Button1`（登录）的 ASP.NET 表单；
     * 课表/主页不会同时出现这两个控件。
     */
    private fun isLoginPage(html: String): Boolean = html.contains("TextBox1") && html.contains("Button1")

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

    /** GBK 解码；老正方响应头不带 charset，body.string() 会按 UTF-8 解出乱码 */
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
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return String(bytes, CHARSET)
        }
    }
}
