package com.zhijian.schedule.core.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 与系统 WebView 共享 Cookie：用户在 WebView 里登录教务后，
 * OkHttp 抓取课表（HTTP 重放）自动带上会话（方案 4.10）。
 */
class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val manager = CookieManager.getInstance()
        cookies.forEach { cookie ->
            manager.setCookie(url.toString(), cookie.toString())
        }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { part ->
            Cookie.parse(url, part.trim())
        }
    }
}
