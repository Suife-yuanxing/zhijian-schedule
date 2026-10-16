package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 教务会话过期的静默恢复：不开 WebView，直接重放统一认证的 GET 链路换回新会话。
 *
 * 强智 jsxsd 的 JSESSIONID 服务端超时后，抓取会落到登录页；只要统一认证的
 * 「N 天免登录」票据（authserver 的 CASTGC）仍在，sso.jsp → authserver →
 * sso.jsp?ticket=… 这条链会自动免密重建会话（登录 POST 被云 WAF 拦截，全程 GET 畅通）。
 * Cookie 经共享 CookieJar 读写，恢复后的会话对 WebView 同样生效。
 *
 * 返回 true 仅表示链路已走完且末页不像登录页；判据是调用方随后的重试抓取。
 */
object JwSessionRecovery {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val MAX_HOPS = 8

    /** 按教务地址尝试恢复；地址非法 / 无 SSO 入口 / 无 CAS 会话 / 网络异常均返回 false */
    suspend fun trySilentRecover(client: OkHttpClient, baseUrl: String): Boolean =
        try {
            val base = ZhengFangAdapter.normalizeBase(baseUrl)
            recoverFrom(client, "${base}sso.jsp")
        } catch (e: Exception) {
            false
        }

    internal suspend fun recoverFrom(client: OkHttpClient, entryUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            // 手动逐跳跟随：学校页面混用 30x 与 JS 跳转，且跨域重定向后要把浏览器 UA 带到统一认证服务器
            val follow = client.newBuilder().followRedirects(false).build()
            var url: String? = entryUrl
            var hops = 0
            while (url != null && hops < MAX_HOPS) {
                hops++
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                follow.newCall(request).execute().use { response ->
                    val location = response.header("Location")
                    when {
                        response.code in 300..399 ->
                            url = location?.let { response.request.url.resolve(it)?.toString() }

                        response.isSuccessful -> {
                            val finalUrl = response.request.url
                            val html = response.body?.string().orEmpty()
                            // 停在统一认证域 = 没有可复用的 CAS 会话，恢复失败
                            if (finalUrl.host.contains("authserver")) return@withContext false
                            if (isSessionDeadPage(html)) return@withContext false
                            val next = scriptRedirectTarget(html)
                                ?: return@withContext true
                            url = finalUrl.resolve(next)?.toString()
                        }

                        else -> url = null
                    }
                }
            }
            false
        }

    /**
     * 页面内跳转目标（强智的 sso.jsp 用 `window.location.href='…'` 跳统一认证，正文仅 200 字节）。
     * 只在页面开头命中才认——正常内容页里散落的 location 赋值（菜单/按钮脚本）不参与跟随。
     */
    private fun scriptRedirectTarget(html: String): String? {
        for (regex in REDIRECT_PATTERNS) {
            val m = regex.find(html) ?: continue
            if (m.range.first > 1024) continue
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) return target
        }
        val meta = META_REDIRECT.find(html) ?: return null
        if (meta.range.first > 1024) return null
        return meta.groupValues[1].trim().ifEmpty { null }
    }

    private val REDIRECT_PATTERNS = listOf(
        Regex("""location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""),
        Regex("""location\.replace\s*\(\s*['"]([^'"]+)['"]"""),
    )

    private val META_REDIRECT = Regex(
        """http-equiv\s*=\s*['"]?refresh['"]?[^>]*content\s*=\s*['"]?\s*\d+\s*;\s*url\s*=\s*([^'">\s]+)""",
        RegexOption.IGNORE_CASE,
    )
}

/** 登录页 / 会话失效页识别：密码表单、强智本地登录表单、会话失效的裸 JSON 错误页 */
internal fun isSessionDeadPage(html: String): Boolean {
    if (html.contains("type=\"password\"", ignoreCase = true)) return true
    if (html.contains("LoginToXk", ignoreCase = true)) return true
    val t = html.trimStart()
    if (t.startsWith("{") && (t.contains("msgContent") || t.contains("\"flag1\""))) return true
    return false
}
