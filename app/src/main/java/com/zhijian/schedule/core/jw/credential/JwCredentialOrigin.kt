package com.zhijian.schedule.core.jw.credential

/**
 * 把页面地址收敛成「凭据匹配用的 origin」：`scheme://host[:port]`，一律小写、丢掉路径与查询串。
 *
 * 为什么要单独抽一层：教务系统的登录页常年带 `?service=...` 之类的回跳参数，
 * 同一次登录里 URL 会变四五次，但 origin 一直不变。凭据按 origin 存、按 origin 取，
 * 才不会因为多一个查询参数就认不出自己的密码。
 *
 * 没写 scheme 的地址（用户随手输入的裸域名）返回 null：这种地址还没经过
 * [com.zhijian.schedule.feature.jw.normalizeJwUrl] 规范化，此时存凭据等于存错 key。
 */
object JwCredentialOrigin {
    fun of(url: String): String? {
        // 空串不需要单独一句 guard：它天然不满足下面的 scheme 判定，
        // 少一个返回点是白拿的（detekt ReturnCount 上限 3，这里正好三处）
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val authority = authorityOf(lower)
        if (authority.isBlank()) return null
        return lower.substringBefore("://") + "://" + authority
    }

    /**
     * 提交页的 origin 是否可信到可以拿来当凭据来源。
     *
     * ## 为什么不能只比「提交页 origin == 配置的教务 origin」
     * 真机实测（东莞职院 2026-11-01）：用户配置 `https://jw.dgpt.edu.cn/jsxsd/`，
     * 打开后学校 302 到 `https://authserver.dgpt.edu.cn/authserver/login?service=...`。
     * 密码是在 **authserver 那页**提交的，所以候选的 origin 就是 authserver。
     * 若此时要求二者严格相等，统一认证学校的候选会被**全部丢弃** —— 功能在这类学校
     * 100% 不工作，而且不会有任何报错（气泡只是不出现）。
     *
     * ## 那放宽到什么程度才安全
     * 只放行「同一注册域」：`jw.dgpt.edu.cn` 与 `authserver.dgpt.edu.cn` 的
     * 最后两段都是 `dgpt.edu.cn`。这条规则挡住的是**真正的跨站**——
     * 大学域名被第三方（统计脚本、广告、假登录页）替换时，注册域一定不同。
     * 同校换子域是正常的 SSO 拓扑，不是风险场景。
     *
     * 教育网域名形如 `xxx.edu.cn` / `xxx.com.cn`，注册域取**三段**；
     * 其余（含纯 IP）取两段。
     */
    fun isTrustedSubmit(
        pageOrigin: String,
        configuredOrigin: String,
    ): Boolean {
        val page = of(pageOrigin) ?: return false
        val configured = of(configuredOrigin) ?: return false
        if (page.equals(configured, ignoreCase = true)) return true
        val pageSite = registrableOf(page) ?: return false
        val configuredSite = registrableOf(configured) ?: return false
        return pageSite.equals(configuredSite, ignoreCase = true)
    }

    /**
     * 取 `scheme://` 之后的 host，按教育网 / 通用二级后缀决定保留几段。
     *
     * 没有「注册域」概念的地址（纯 IPv4 / IPv6）原样返回：它们只靠上面那次精确比对判定。
     * 单段 host（`localhost` 这类）与两段 host（`example.com`）同样原样返回。
     */
    private fun registrableOf(origin: String): String? {
        val host = origin.substringAfter("://").substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        val isNumericHost = host.all { it.isDigit() || it == '.' }
        val isBracketedV6 = host.startsWith("[")
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (isNumericHost || isBracketedV6 || parts.size <= 2) return host
        val secondLevel = parts[parts.size - 2]
        val suffix = parts[parts.size - 1]
        // edu.cn / com.cn / gov.cn / org.cn 这类「二级公共后缀」：注册域是后三段
        val keep = if (suffix == "cn" && secondLevel in CN_SECOND_LEVEL) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }

    /** `*.cn` 下按「后三段才是注册域」处理的二级后缀 */
    private val CN_SECOND_LEVEL = setOf("edu", "com", "gov", "org", "net", "ac")

    /** 取出 `scheme://` 与第一个路径分隔符之间的主机部分，并剥掉 user:pass@ 前缀 */
    private fun authorityOf(lowerUrl: String): String =
        lowerUrl
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
}
