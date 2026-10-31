package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * 青果软件 KINGOSOFT 教务适配器（经典 `/jwweb/` 与 V17 `/qzhgjw/`）。
 *
 * 登录由 WebView 完成，本适配器只带共享 Cookie 重放课表查询。
 * 青果课表页是服务端渲染的大表格，页内常见「框架页套课表页」，
 * 解析统一交给 [HtmlTableScheduleParser]（不依赖厂商专用 id）。
 *
 * 候选路径按部署版本排列；全部失败时给出「请先进个人课表页」的明确提示，
 * 不静默回退到其他厂商解析器。
 */
object QingGuoAdapter : JwAdapter {
    override val id = "qingguo"
    override val name = "青果 KINGOSOFT"

    override val steps =
        AdapterSteps(
            timetablePage = "课表查询 → 学生课表",
            note = "青果菜单在页面左侧，展开「课表查询」后选学生课表",
        )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return 0
        val path = parsed.encodedPath.lowercase()
        val score =
            when {
                path.contains("/jwweb/") || path.endsWith("/jwweb") -> 100
                path.contains("/qzhgjw/") || path.endsWith("/qzhgjw") -> 95
                path.contains("xskbcx.aspx") || path.contains("xskbcx_query") -> 90
                SchoolCatalog.byHost(parsed.host)?.adapterId == id -> 85
                else -> 0
            }
        return score
    }

    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            val referer = "${base}xsMain.aspx"
            val candidates =
                listOf(
                    "${base}xskbcx/xskbcx.aspx",
                    "${base}xskbcx/xskbcx_query.aspx",
                    "${base}xskbcx/xskbcx_query.action",
                    "${base}xskbcx/xskbcx_list.aspx",
                )
            var lastError: String? = null
            for (url in candidates) {
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
                val page = resolveFrames(client, html, url, referer)
                val drafts = HtmlTableScheduleParser.parse(page, totalWeeks)
                if (drafts.isNotEmpty()) {
                    return@withContext JwFetchResult(drafts, SectionTimes.fromHtml(page))
                }
                lastError = "未找到课表表格：$url"
            }
            throw JwException.ParseFailed(
                "青果未返回课表：${lastError ?: "请先在网页中进入个人课表页再导入"}",
            )
        }

    override val gradeFetchSupported = true

    /**
     * 全部学期成绩：青果的成绩页在 `kscj/` 目录（默认列表页即全学期，
     * 或当前学期），HTML 表格交给 [GradeParser.parseGeneric] 按表头解析。
     * 候选未命中（部署差异）返回空，按未抓到静默跳过。
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
                    "${base}kscj/cjcx_list.aspx",
                    "${base}kscj/cjcx.aspx",
                    "${base}kscj/cjcx_list_rpt.aspx",
                ),
            referer = "${base}xsMain.aspx",
            userAgent = UA,
            isDeadPage = ::isLoginPage,
        )
    }

    /**
     * 青果常见「框架页套课表页」，最多向下钻两层；解析失败时返回原页面，
     * 由调用方统一给出 ParseFailed，不在这里吞掉登录态判断。
     */
    @Suppress("SwallowedException")
    private fun resolveFrames(
        client: OkHttpClient,
        html: String,
        pageUrl: String,
        referer: String,
    ): String {
        var currentHtml = html
        var currentUrl = pageUrl
        repeat(2) {
            val target = frameTarget(currentHtml, currentUrl) ?: return currentHtml
            val next =
                try {
                    get(client, target, currentUrl.ifBlank { referer })
                } catch (e: JwException) {
                    throw e
                } catch (e: IOException) {
                    return currentHtml
                }
            if (isLoginPage(next)) throw JwException.NeedsLogin()
            currentHtml = next
            currentUrl = target
        }
        return currentHtml
    }

    /** 取页面里第一个可跟随的 iframe/frame 地址；没有则返回 null */
    private fun frameTarget(
        html: String,
        pageUrl: String,
    ): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val frame = doc.selectFirst("iframe[src], frame[src]") ?: return null
        val src = frame.attr("src").trim()
        if (src.isEmpty() || src.startsWith("javascript:", ignoreCase = true)) return null
        return runCatching { pageUrl.toHttpUrlOrNull()?.resolve(src)?.toString() }.getOrNull()
    }

    private fun isLoginPage(html: String): Boolean {
        if (isSessionDeadPage(html)) return true
        return html.contains("<title>登录</title>") && !html.contains("<table")
    }

    fun normalizeBase(url: String): String {
        val parsed = parseUrl(url) ?: throw JwException.NetworkFailed("教务地址无效")
        val path = parsed.encodedPath
        val jwweb = path.indexOf("/jwweb/")
        val qzhgjw = path.indexOf("/qzhgjw/")
        val basePath =
            if (jwweb >= 0) {
                path.substring(0, jwweb + "/jwweb/".length)
            } else if (qzhgjw >= 0) {
                path.substring(0, qzhgjw + "/qzhgjw/".length)
            } else if (path.endsWith("/jwweb")) {
                "$path/"
            } else if (path.endsWith("/qzhgjw")) {
                "$path/"
            } else {
                SchoolCatalog.knownBasePath(parsed.host)
                    ?: throw JwException.NetworkFailed("请在教务地址中包含 /jwweb/ 路径")
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
}
