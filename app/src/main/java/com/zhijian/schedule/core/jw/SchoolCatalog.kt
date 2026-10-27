package com.zhijian.schedule.core.jw

import java.io.BufferedReader

/**
 * 学校档案：只解决两件事——
 * 1. 用户只输入裸域名时，补全该校真实存在的教务子路径；
 * 2. 「选校自动填 URL」的检索与展示。
 *
 * 识别本身仍以 URL 路径指纹为主（/jsxsd/、/jwglxt/、/eams/、/academic/），
 * 不靠域名猜厂商；[verified] 为 false 的条目只表示「社区目录收录了入口」，
 * 不代表本校当前实测可用，UI 必须区分展示。
 *
 * 本文件保持纯 JVM（不依赖 Android API）：assets 读取在 `SchoolCatalogAssets.kt`
 * 的 `ensureLoaded` 扩展里，单测通过 [loadFrom] 直接喂目录文本。
 */
data class SchoolProfile(
    val id: String,
    val name: String,
    val hosts: Set<String>,
    /** 教务基路径，必须以 / 开头、以 / 结尾 */
    val basePath: String,
    val adapterId: String,
    /** 拼音检索串（目录别名全拼），用于搜索与首字母分组 */
    val pinyin: String = "",
    /** true = 目录标记为「已实现配置」；false = 仅「待验证·可尝试」 */
    val verified: Boolean = true,
    /** 用户在网页里打开的教务入口；为空时由 [hosts] + [basePath] 拼出 */
    val entryUrl: String = "",
) {
    /** 选校后要填进地址栏的完整地址 */
    fun entry(): String =
        entryUrl.ifBlank {
            hosts.firstOrNull()?.let { "https://$it$basePath" }.orEmpty()
        }
}

/**
 * 学校目录。内置条目（有实测证据的学校）与 assets 目录（社区快照）合并，
 * 用 [ensureLoaded] 懒加载 assets——不带 Context 的老接口在未加载时只看到内置条目，
 * 不会崩也不会阻塞主线程。
 */
object SchoolCatalog {
    private const val FIELD_COUNT = 5
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_URL_LENGTH = 512
    private const val DEFAULT_LIMIT = 80
    private const val MAX_LIMIT = 400
    private const val OTHER_INITIAL = '#'
    private const val MIN_INITIAL = 'A'
    private const val MAX_INITIAL = 'Z'

    /** 检索串里多个别名（缩写 / 全拼）之间的分隔符 */
    private const val SEARCH_SEPARATOR = "/"

    /** 有真实抓取证据的学校；assets 目录不含它们时也能工作 */
    private val builtIn =
        listOf(
            SchoolProfile(
                id = "dgpt",
                name = "东莞职业技术学院",
                hosts = setOf("jw.dgpt.edu.cn"),
                basePath = "/jsxsd/",
                adapterId = QiangZhiAdapter.id,
                pinyin = "dongguanzhiyejishuxueyuan",
                entryUrl = "https://jw.dgpt.edu.cn/jsxsd/",
            ),
        )

    @Volatile
    private var loaded = false

    @Volatile
    private var catalog: List<SchoolProfile> = builtIn

    /** 每次替换 [catalog] 时同步重建，避免 lazy 缓存读到加载前的旧表 */
    @Volatile
    private var hostIndex: Map<String, SchoolProfile> = indexOf(builtIn)

    val isLoaded: Boolean
        get() = loaded

    /** 供测试、生成脚本与 assets 加载器直接喂数据（不经过 Android API） */
    fun loadFrom(text: String) {
        synchronized(this) {
            catalog = merge(builtIn, readCatalog(text.reader().buffered()))
            hostIndex = indexOf(catalog)
            loaded = true
        }
    }

    /** 仅测试使用：清空 assets 目录内容，回到内置条目，避免用例之间串味 */
    internal fun resetForTest() {
        synchronized(this) {
            catalog = builtIn
            hostIndex = indexOf(builtIn)
            loaded = false
        }
    }

    fun all(): List<SchoolProfile> = catalog

    fun byHost(host: String): SchoolProfile? {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return null
        return hostIndex[h]
    }

    fun knownBasePath(host: String): String? = byHost(host)?.basePath

    /**
     * 选校检索：学校名 / 拼音 / 域名三路匹配。
     * 排序：已验证优先，其次前缀命中，其次短名优先。
     */
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<SchoolProfile> {
        val q = query.trim().lowercase()
        val capped = limit.coerceIn(1, MAX_LIMIT)
        if (q.isEmpty()) return catalog.take(capped)
        return catalog
            .asSequence()
            .mapNotNull { profile ->
                val score = matchScore(profile, q)
                if (score > 0) profile to score else null
            }.sortedWith(
                compareByDescending<Pair<SchoolProfile, Int>> { it.first.verified }
                    .thenByDescending { it.second }
                    .thenBy { it.first.name.length },
            ).take(capped)
            .map { it.first }
            .toList()
    }

    /** 首字母分组（A–Z，其余归 #），组内按拼音排序 */
    fun groups(): List<Pair<Char, List<SchoolProfile>>> =
        catalog
            .groupBy { initial(it) }
            .toList()
            .sortedBy { (initial, _) -> if (initial == OTHER_INITIAL) 'Z' + 1 else initial }
            .map { (initial, list) -> initial to list.sortedBy { it.pinyin } }

    private fun initial(profile: SchoolProfile): Char {
        val first = profile.pinyin.firstOrNull()?.uppercaseChar() ?: return OTHER_INITIAL
        return if (first in MIN_INITIAL..MAX_INITIAL) first else OTHER_INITIAL
    }

    private fun matchScore(
        profile: SchoolProfile,
        q: String,
    ): Int {
        val name = profile.name.lowercase()
        // pinyin 是「缩写/全拼」多段拼接（如 hfut/hefeigongyedaxue），逐段比
        val parts = profile.pinyin.split(SEARCH_SEPARATOR)
        return when {
            parts.any { it.startsWith(q) } -> JwMatchScore.EXACT
            name.startsWith(q) -> JwMatchScore.ENDPOINT
            parts.any { it.contains(q) } -> JwMatchScore.HINT
            name.contains(q) -> JwMatchScore.WEAK
            profile.hosts.any { it.contains(q) } || profile.entryUrl.lowercase().contains(q) ->
                JwMatchScore.HOST
            else -> JwMatchScore.NONE
        }
    }

    private fun merge(
        base: List<SchoolProfile>,
        extra: List<SchoolProfile>,
    ): List<SchoolProfile> {
        val merged = LinkedHashMap<String, SchoolProfile>()
        for (profile in base + extra) {
            // 同域名的不同校区/不同条目要各自保留，因此 key 用「域名 + 校名」
            val host = profile.hosts.firstOrNull().orEmpty()
            val key = "$host|${profile.name}"
            val old = merged[key]
            // 同名同域名时内置条目更可信，内置优先
            if (old == null || (!old.verified && profile.verified)) merged[key] = profile
        }
        return merged.values.toList()
    }

    private fun indexOf(profiles: List<SchoolProfile>): Map<String, SchoolProfile> =
        profiles
            .flatMap { profile -> profile.hosts.map { it.lowercase() to profile } }
            .toMap()

    private fun readCatalog(reader: BufferedReader): List<SchoolProfile> =
        reader
            .useLines { lines ->
                lines.mapNotNull(::parseLine).toList()
            }

    private fun parseLine(line: String): SchoolProfile? {
        val fields = line.split('\t')
        if (line.isBlank() || fields.size < FIELD_COUNT) return null
        val name = fields[0].trim()
        val url = fields[2].trim()
        val adapterId = fields[3].trim()
        if (!isUsable(name, url, adapterId)) return null
        val host = hostOf(url)
        return SchoolProfile(
            id = "$adapterId:${host.ifEmpty { name }}",
            name = name,
            hosts = if (host.isEmpty()) emptySet() else setOf(host),
            basePath = basePathOf(url),
            adapterId = adapterId,
            pinyin = fields[1].trim().lowercase(),
            verified = fields[4].trim() == "1",
            entryUrl = url,
        )
    }

    private fun isUsable(
        name: String,
        url: String,
        adapterId: String,
    ): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_NAME_LENGTH &&
            url.length in 1..MAX_URL_LENGTH &&
            adapterId.isNotEmpty()

    private fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        return withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    }

    /** 从入口 URL 抠出目录协议基路径；认不出来时退回根路径 */
    private fun basePathOf(url: String): String {
        val path = "/" + url.substringAfter("://", url).substringAfter('/', "")
        val known =
            listOf(
                "/jsxsd/",
                "/jwglxt/",
                "/jwweb/",
                "/qzhgjw/",
                "/eams/",
                "/academic/",
                "/jwapp/",
            )
        return known.firstOrNull { path.contains(it, ignoreCase = true) } ?: "/"
    }
}
