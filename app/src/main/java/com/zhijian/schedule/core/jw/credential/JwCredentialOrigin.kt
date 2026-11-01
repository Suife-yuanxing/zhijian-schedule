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

    /** 取出 `scheme://` 与第一个路径分隔符之间的主机部分，并剥掉 user:pass@ 前缀 */
    private fun authorityOf(lowerUrl: String): String =
        lowerUrl
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
}
