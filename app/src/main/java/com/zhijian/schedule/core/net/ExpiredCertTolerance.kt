package com.zhijian.schedule.core.net

/**
 * 校方证书过期后的**受限放行**策略（纯逻辑，可单测：不碰 Android、不联网）。
 *
 * ## 为什么需要这个策略
 * 广西工程职业学院门户 `zhxy.gxgcedu.com` 的 Let's Encrypt 证书有效期是
 * 2026-07-30 → 2026-10-28，学校没有续期。证书一过期，WebView 报
 * `SSL_DATE_INVALID`、OkHttp 抛 `CertificateExpiredException`，
 * 学生连登录页都打不开——而这不是用户能修的东西。
 *
 * 所以对**这一台主机**、**只针对"过期"这一种错误**、**只在过期后的有限天数内**
 * 放行；其它任何情况（主机不匹配、自签名、签发者未知、过期太久）照旧拦下。
 *
 * ## 三道闸门缺一不可
 * 1. [isToleratedHost]：主机名精确命中，不做后缀匹配——`gxgcedu.com` 不算；
 * 2. [withinGrace]：只看 `notAfter`，过期天数上限 [GRACE_DAYS]，且尚未过期的一律不走这条路；
 * 3. [sanCoversHost]：证书主体名必须覆盖该主机，防止拿别的站点的过期证书来顶。
 *
 * 放行时 [sslNotice] 给用户一句实话：证书确实过期了，这次是 App 让它过的。
 *
 * ## 为什么不是"关掉校验"
 * 关掉校验会让**所有**站点、**所有**证书错误都通过，包括真正的中间人。
 * 这里的放行面被压到"一台主机 + 一种错误 + 一个时间窗"，且随时可以删掉：
 * 学校续期后 [TOLERATED_HOSTS] 里的条目直接失效（未过期就不会进这条分支）。
 */
@Suppress("TooManyFunctions") // 全部是同一个判定的小步骤：主机 / 时间窗 / SAN / 文案
object ExpiredCertTolerance {
    /** 放行窗口：过期超过这么多天就彻底拦下，逼学校续期，也避免"永远凑合" */
    const val GRACE_DAYS: Int = 30

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000L
    private const val GRACE_MS: Long = GRACE_DAYS * DAY_MS

    /** X.509 SAN 里的 dNSName 类型号（RFC 5280 §4.2.1.6） */
    private const val SAN_DNS: Int = 2

    /**
     * 允许走"过期放行"的主机。
     *
     * 只放门户这一台：`/academic/` 是同一主机的路径，不需要额外条目。
     * 学校续期后无需改代码——证书未过期时根本不会走到这条分支。
     */
    private val TOLERATED_HOSTS: Set<String> = setOf("zhxy.gxgcedu.com")

    /** 主机是否在放行名单里；大小写与首尾空白不敏感，null / 空串一律 false */
    fun isToleratedHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase().orEmpty()
        return normalized.isNotEmpty() && normalized in TOLERATED_HOSTS
    }

    /**
     * 从 URL 里取 authority 的主机段（小写）。
     *
     * 只认真正的 authority：路径里出现的同名片段不算（`https://evil/zhxy.gxgcedu.com` 的
     * 主机是 evil）。没有 scheme 的裸串直接返回 null——WebView / OkHttp 给的地址总是完整的。
     */
    fun hostOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        val separator = trimmed.indexOf("://")
        if (separator <= 0) return null
        val authority =
            trimmed
                .substring(separator + 3)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        val host = authority.substringAfter('@').substringBefore(':').lowercase()
        return host.ifBlank { null }
    }

    /**
     * 过期时刻是否落在放行窗口内。
     *
     * 尚未过期（[nowMs] <= [expiredAtMs]）返回 false：那种情况根本不该进这条分支，
     * 真进来了说明判定链出了问题，宁可拦下。
     */
    fun withinGrace(
        expiredAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs > expiredAtMs && nowMs - expiredAtMs <= GRACE_MS

    /** 已过期天数（向上取整，最小 0）：文案里要说清"过期多久"，不给用户一个模糊的"已过期" */
    fun expiredDays(
        expiredAtMs: Long,
        nowMs: Long,
    ): Int {
        val elapsed = nowMs - expiredAtMs
        if (elapsed <= 0) return 0
        return ((elapsed + DAY_MS - 1) / DAY_MS).toInt()
    }

    /**
     * WebView 侧的总判定：**仅在 `SslError.SSL_DATE_INVALID` 分支里调用**。
     *
     * 其它 SSL 错误（主机名不匹配、自签名、签发者未知）不该走到这里；
     * 即便走到了，主机名 + 时间窗两道闸门也会把它们挡在门外。
     */
    fun shouldProceedOnSslError(
        url: String?,
        expiredAtMs: Long?,
        nowMs: Long,
    ): Boolean =
        expiredAtMs != null &&
            isToleratedHost(hostOf(url)) &&
            withinGrace(expiredAtMs, nowMs)

    /** OkHttp 侧的总判定：TrustManager 拿不到主机名，只能看叶子证书自己写了谁 */
    fun namesToleratedHost(sanEntries: Collection<List<*>>?): Boolean =
        TOLERATED_HOSTS.any { sanCoversHost(sanEntries, it) }

    /**
     * OkHttp 侧的总判定：证书链校验失败后，是否放行。
     *
     * 与 WebView 那条（[shouldProceedOnSslError]）判据完全一致，只是**主机名的来源不同**：
     * TrustManager 拿不到连接目标，只能看叶子证书自己声明的 SAN。
     * 主机名不匹配的风险由 OkHttp 自己的主机名校验兜住——那一步没被关掉。
     */
    fun shouldProceedOnChainFailure(
        sanEntries: Collection<List<*>>?,
        notAfterMs: Long?,
        nowMs: Long,
    ): Boolean =
        notAfterMs != null &&
            namesToleratedHost(sanEntries) &&
            withinGrace(notAfterMs, nowMs)

    /**
     * 证书主体名（SAN）是否覆盖 [host]。
     *
     * 只看 dNSName 条目：IP 条目（类型 7）、通配符跨两级（`*.a.com` 不覆盖 `x.a.com`
     * 之外的 `y.x.a.com`）都不算命中。
     */
    fun sanCoversHost(
        sanEntries: Collection<List<*>>?,
        host: String?,
    ): Boolean {
        val target = host?.trim()?.lowercase().orEmpty()
        if (target.isEmpty() || sanEntries == null) return false
        return sanEntries.any { entry -> dnsNameOf(entry)?.let { covers(it, target) } == true }
    }

    /**
     * 给用户看的放行说明。
     *
     * 三件事一次说清：哪台主机的证书过期了、过期多久、这次是 App 放行的。
     * 不写成"证书错误已忽略"——那是把风险糊成一句技术黑话。
     */
    fun sslNotice(
        expiredAtMs: Long,
        nowMs: Long,
    ): String {
        val host = TOLERATED_HOSTS.first()
        val days = expiredDays(expiredAtMs, nowMs)
        val span = if (days <= 0) "今天刚过期" else "已过期 " + days + " 天"
        return host + " 的 HTTPS 证书" + span + "（学校未续期），本次已放行访问；" +
            "续期后这条提示会自动消失"
    }

    private fun dnsNameOf(entry: List<*>): String? {
        if (entry.size < 2) return null
        if ((entry[0] as? Number)?.toInt() != SAN_DNS) return null
        return (entry[1] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /** 精确相等，或单层通配符：`*.gxgcedu.com` 覆盖 `zhxy.gxgcedu.com`，不覆盖 `a.zhxy.gxgcedu.com` */
    private fun covers(
        dnsName: String,
        host: String,
    ): Boolean {
        if (dnsName == host) return true
        if (!dnsName.startsWith("*.")) return false
        val suffix = dnsName.substring(1)
        if (!host.endsWith(suffix) || host.length <= suffix.length) return false
        return !host.dropLast(suffix.length).contains('.')
    }
}
