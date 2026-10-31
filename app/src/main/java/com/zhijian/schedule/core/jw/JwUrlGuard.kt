package com.zhijian.schedule.core.jw

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 教务请求 URL 的安全闸门：教务地址来自用户输入（地址栏 / 选校目录），
 * 成绩等新抓取路径在拼 URL 发请求前先过这里——只放行 http(s) 的公网主机，
 * 拒绝 localhost / 环回 / 私有网段与保留地址（SSRF 基本面）。
 *
 * 校内网部署（10.x 等私有地址的教务）会在这里被拦下：成绩是 best-effort
 * 旁路数据，静默跳过不影响课表；主抓取链路维持现状（WebView 同源校验）。
 */
object JwUrlGuard {
    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"

    // IPv4 私有 / 保留段（按首段或首两段划分）
    private const val V4_LOOPBACK_FIRST = 127
    private const val V4_PRIVATE10_FIRST = 10
    private const val V4_CLASSB_SECOND_FIRST = 16
    private const val V4_CLASSB_SECOND_LAST = 31
    private const val V4_CLASSB_FIRST = 172
    private const val V4_CLASSC_FIRST = 192
    private const val V4_CLASSC_SECOND = 168
    private const val V4_LINKLOCAL_FIRST = 169
    private const val V4_LINKLOCAL_SECOND = 254
    private const val V4_CGNAT_FIRST = 100
    private const val V4_CGNAT_SECOND_FIRST = 64
    private const val V4_CGNAT_SECOND_LAST = 127
    private const val V4_MULTICAST_FIRST = 224
    private const val V4_OCTETS = 4

    // IPv6：仅全球单播 2000::/3 可出网
    private const val V6_GLOBAL_MASK = 0xE000u
    private const val V6_GLOBAL_PREFIX = 0x2000u

    /** 校验并返回可安全请求的 URL；不合规返回 null */
    fun safeHttpUrl(raw: String): HttpUrl? {
        val parsed = raw.toHttpUrlOrNull()
        return parsed?.takeIf { isHttpScheme(it) && isPublicHost(it.host.lowercase()) }
    }

    private fun isHttpScheme(url: HttpUrl): Boolean =
        url.scheme.lowercase() in setOf(SCHEME_HTTP, SCHEME_HTTPS)

    private fun isPublicHost(host: String): Boolean =
        host.isNotEmpty() && if (host.contains(':')) isPublicIpv6(host) else !isRejectedV4(host)

    private fun isRejectedV4(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || octetsRejected(host)

    private fun octetsRejected(host: String): Boolean {
        val parts = host.split('.')
        val first = parts.getOrNull(0)?.toIntOrNull()
        val second = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != V4_OCTETS || first == null || second == null) {
            return false // 域名不做 DNS 层判断，交给网络层
        }
        return first == V4_LOOPBACK_FIRST ||
            first == V4_PRIVATE10_FIRST ||
            (first == V4_CLASSB_FIRST && second in V4_CLASSB_SECOND_FIRST..V4_CLASSB_SECOND_LAST) ||
            (first == V4_CLASSC_FIRST && second == V4_CLASSC_SECOND) ||
            (first == V4_LINKLOCAL_FIRST && second == V4_LINKLOCAL_SECOND) ||
            (first == V4_CGNAT_FIRST && second in V4_CGNAT_SECOND_FIRST..V4_CGNAT_SECOND_LAST) ||
            first >= V4_MULTICAST_FIRST
    }

    private fun isPublicIpv6(host: String): Boolean {
        // 环回（::1）、链路本地（fe80::/10）、唯一本地（fc00::/7）都不落在 2000::/3
        val first = host.split(':').firstOrNull { it.isNotBlank() } ?: return false
        val value = first.toUIntOrNull(16) ?: return false
        return value and V6_GLOBAL_MASK == V6_GLOBAL_PREFIX
    }
}
