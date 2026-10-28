package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 广西工程职业学院（`zhxy.gxgcedu.com`）的校级适配器。
 *
 * ## 为什么单列一所学校
 * 校方 2022 年通知里的入口 `http://edu.gxgcedu.com/` 已经不解析（NXDOMAIN）；
 * 现行入口是智慧校园统一认证门户 `https://zhxy.gxgcedu.com/login`，登录后从门户进教务。
 * 这与目录里任何一家厂商的「登录页 = 教务首页」形态都不同：**门户主机本身不是教务**，
 * 玄机在同域的 `/academic/` 前缀上——那正是 [UrpAdapter] 的强特征路径，教务后端
 * 还常挂在校内 `8088` 端口（校外实测 TCP 被过滤）。
 *
 * 所以本适配器不重写解析：课表仍交给 [UrpAdapter]（`/academic/` + `showTimetable.do`）。
 * 它只补通用路径补不了的两件事：
 * 1. 门户首页 / 登录页 / `/academic/` 能识别成本校，而不是「未识别教务系统」；
 * 2. 抓取前探一次 `/academic/`：若 302 指向同主机的非标准端口、且那个端口连不上，
 *    说明要连校园网 / WebVPN，直接把这句话告诉用户，而不是抛一个「URP 未返回课表」
 *    让人以为学校不支持本校。
 * 已进课表页（`showTimetable.do`）时 [match] 打分归零，让 URP 的路径指纹接管，
 * 避免把带学期参数的地址改回门户根。
 *
 * ## 不做的事
 * 门户统一认证有图形验证码与微信 / 企业微信 / 钉钉扫码，[ssoEntry] 一律返回 null：
 * 静默重放登录只会打乱账号风控，宁可让用户在网页里点一次。
 */
object GuangXiGcAdapter : JwAdapter {
    override val id = "gxgc"
    override val name = "广西工程职业学院"

    override val steps =
        AdapterSteps(
            timetablePage = "教务门户 → 我的课表",
            note = "校外直连会跳到校内非标准端口，打不开时请先连校园网或 WebVPN 再登录",
        )

    /** 门户与教务同域；识别只看路径，不猜端口 */
    const val HOST = "zhxy.gxgcedu.com"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 这两个路径已经是 URP 的领地，交给它自己的指纹与基路径规范化 */
    private val URP_MARKS = listOf("showtimetable", "xkaction")

    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443

    /** 端口探测的等待上限：比抓取超时短得多，探测不能拖慢正常抓取 */
    private const val CONNECT_TIMEOUT_MS = 2500

    /**
     * 端口连不上时的提示：把真实端口写进文案，学校换端口时不会说谎。
     * 后半句同时覆盖「校内但该端口被禁」的情况，避免用户在校内被劝去连校园网。
     */
    fun offCampusHint(port: Int): String =
        "教务端口 " + port + " 连不上：校外请连校园网或学校 WebVPN 后再试；" +
            "已在校内则确认网络未禁用该端口"

    override fun match(url: String): Int {
        val parsed = parseUrl(url) ?: return JwMatchScore.NONE
        if (!parsed.host.equals(HOST, ignoreCase = true)) return JwMatchScore.NONE
        val path = parsed.encodedPath.lowercase()
        if (URP_MARKS.any { path.contains(it) }) return JwMatchScore.NONE
        return JwMatchScore.EXACT
    }

    /**
     * 先探教务前缀，再交给 [UrpAdapter] 真正抓取。
     *
     * 门户把 `/academic/` 302 到同一主机的非标准端口（实测 :8088）时，那个端口出校即被过滤。
     * 但这跳转**不稳定**：同一秒内的 6 次请求里 5 次 302 到 HTTPS 登录页、1 次到 :8088，
     * 说明门户前后端的配置不一致。所以这里只做尽力而为的探测——撞不上就照常走 URP，
     * 由 URP 报它自己的错，绝不为了「可能有帮助的提示」把正常抓取拦下来。
     */
    override suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult {
        val blocked = withContext(Dispatchers.IO) { blockedPort(client, baseUrl) }
        if (blocked != null) throw JwException.NetworkFailed(offCampusHint(blocked))
        return UrpAdapter.fetchSchedule(client, baseUrl, totalWeeks)
    }

    /**
     * 从 30x 的 Location 里读「校外被拦」的证据：同主机的非标准端口。
     *
     * 纯字符串判断，便于单测：不联网、不开 WebView。
     * 相对地址（`/login`）、缺省端口（80 / 443）、别的学校的主机都不算证据——
     * 那些都是正常跳转，报「请连校园网」只会误导。
     */
    fun offCampusPort(
        location: String?,
        host: String,
    ): Int? {
        val target = location?.trim().orEmpty()
        if (!target.startsWith("http", ignoreCase = true)) return null
        val authority =
            target
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        // 无冒号 = 无端口；有冒号时最后一段必须是数字端口，主机段必须与本校门户一致
        val samePortal =
            authority.contains(':') &&
                authority.substringBeforeLast(':').equals(host.trim(), ignoreCase = true)
        val port = authority.substringAfterLast(':').toIntOrNull()
        return port?.takeIf { samePortal && it != DEFAULT_HTTP_PORT && it != DEFAULT_HTTPS_PORT }
    }

    /**
     * 「校外被拦」的判定必须两步：先看到跳到校内端口，**再**确认那个端口确实连不上。
     *
     * 只看跳转是不够的：门户前面是负载均衡，实测 6 次请求里有 5 次 302 到
     * HTTPS 登录页、1 次直接 302 到 :8088——两个后端配置不一致。
     * 所以这条提示是**尽力而为**：撞上那个后端才判得出来，撞不上就照常走 URP，
     * 由 URP 报它自己的错。宁可漏报，也不能在校内误报把人劝去连校园网。
     * 而校内用户同样会撞上那个后端，只有真去连一下端口才能区分两种情况。
     */
    internal fun blockedPort(
        client: OkHttpClient,
        baseUrl: String,
    ): Int? {
        val port = offCampusPortOf(client, baseUrl) ?: return null
        val host = parseUrl(baseUrl)?.host ?: return null
        return port.takeIf { !isPortReachable(host, it) }
    }

    /** 纯 TCP 连通性；域名解析失败、超时、被过滤都算不可达 */
    private fun isPortReachable(
        host: String,
        port: Int,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)

    /** 探测实现；单测用 MockWebServer 直接调它，避免为了一次 GET 把整条抓取链路搭进用例 */
    internal fun offCampusPortProbe(
        client: OkHttpClient,
        baseUrl: String,
    ): Int? = offCampusPortOf(client, baseUrl)

    private fun offCampusPortOf(
        client: OkHttpClient,
        baseUrl: String,
    ): Int? =
        runCatching {
            val base = portalBase(baseUrl) ?: return@runCatching null
            // 与「进来的那台门户」比主机，而不是与常量比：WebVPN / 镜像部署会换域名
            val expectedHost = parseUrl(baseUrl)?.host ?: HOST
            val probe =
                Request
                    .Builder()
                    .url("${base}academic/")
                    .header("User-Agent", UA)
                    .get()
                    .build()
            // 逐跳自己看 Location：跟随一跳就看不到了
            client
                .newBuilder()
                .followRedirects(false)
                .build()
                .newCall(probe)
                .execute()
                .use { response -> offCampusPort(response.header("Location"), expectedHost) }
        }.getOrNull()

    /** 门户根：丢掉 path / query / fragment，探测从这里出发 */
    private fun portalBase(url: String): String? {
        val parsed = parseUrl(url) ?: return null
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
}
