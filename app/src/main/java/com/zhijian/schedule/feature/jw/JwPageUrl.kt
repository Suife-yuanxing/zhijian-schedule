package com.zhijian.schedule.feature.jw

/**
 * 教务导入页的 URL 判定与规范化。
 *
 * 这里全是纯字符串逻辑：不碰 WebView、不碰 Compose，因此可以放进本机 JVM 单测
 * （见同包 JwPageUrlTest）。判定口径与改造前逐字一致——上方引导条的三档动态文案
 * （登录页 / 统一认证页 / 已进入教务系统）完全依赖这三条判定。
 */

/** WebView 当前停在登录页：LoginToXk / login_slogin / 裸 jsxsd 尾巴都算 */
internal fun isJwLoginPage(pageUrl: String): Boolean {
    val u = pageUrl
    if (u.isBlank()) return false
    return u.contains("LoginToXk", true) ||
        u.contains("login_slogin", true) ||
        u.trimEnd('/').endsWith("/jsxsd") ||
        u.contains("login", true)
}

/** 登录后进入主框架 / 课表页（引导条切到「点右下抓取课表」） */
internal fun isJwLoggedInPage(pageUrl: String): Boolean =
    pageUrl.contains("xsMain", true) ||
        pageUrl.contains("xskb", true) ||
        pageUrl.contains("index_initMenu", true)

/** 停在统一身份认证登录页（sso.jsp 自动跳转而来） */
internal fun isJwAuthserverPage(pageUrl: String): Boolean =
    pageUrl.contains("authserver", true)

/**
 * 用户可能只输入裸域名；WebView 需要完整 scheme 才能当网址加载。
 *
 * @param blankPage 未配置时的空白页常量（[JwImportViewModel.BLANK_PAGE]）：必须原样返回，
 *   不能被补成 https://about:blank
 */
internal fun normalizeJwUrl(value: String, blankPage: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> ""
        trimmed.startsWith("http", ignoreCase = true) -> trimmed
        trimmed == blankPage -> trimmed
        else -> "https://$trimmed"
    }
}
