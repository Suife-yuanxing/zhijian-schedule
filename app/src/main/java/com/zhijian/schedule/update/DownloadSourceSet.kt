package com.zhijian.schedule.update

/** 一个下载源；[id] 用于判重与日志，[label] 是给用户看的来源标签 */
data class UpdateSource(val id: String, val label: String, val url: String)

/**
 * 下载源链的**纯逻辑**：从 `browser_download_url` 模板化生成候选，不改 UpdateChecker、不加额外 API 调用。
 *
 * 硬性安全约束（设计 §3.2）：**没有可用 digest 时只保留直连**。
 * 判据是 [ResumableDownload.normalizeDigest] 而不是「非空」—— 格式非法的摘要等于没有摘要，
 * 那种情况下走镜像就是「第三方源 + 只校验长度」，必须在代码层禁掉。
 */
object DownloadSourceSet {

    /** 直连源；永远排第一，且它的 url 就是 request 里的原始地址 */
    const val DIRECT_ID = "direct"
    const val DIRECT_LABEL = "直连"

    private const val GITHUB_PREFIX = "https://github.com/"

    /**
     * 镜像常量表。实测 9 个主流加速站里只有 gh-proxy.com 活着（且只有 4.6 KB/s），
     * 所以这里只留一个、默认关闭、只当「直连彻底跑不通」时的最后退路。用户可自行修改本表。
     */
    val MIRRORS: List<UpdateSource> = listOf(
        UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"),
    )

    /**
     * 这次下载可以依次尝试的源。来源变了必须清空重下（设计 §3.3），
     * 所以候选里只有「同一个文件的不同前缀」，不会混进别的内容。
     */
    fun availableSources(
        directUrl: String,
        digest: String?,
        mirrors: List<UpdateSource>,
    ): List<UpdateSource> {
        val direct = UpdateSource(DIRECT_ID, DIRECT_LABEL, directUrl)
        if (ResumableDownload.normalizeDigest(digest) == null) return listOf(direct)
        return listOf(direct) + mirrors.map { it.copy(url = withPrefix(it.url, directUrl)) }
    }

    /** 把直连地址挂到镜像前缀后面（`gh-proxy.com/<完整原始 URL>`）；非 GitHub 地址原样返回 */
    fun withPrefix(prefix: String, url: String): String {
        val base = prefix.trimEnd('/')
        if (!url.startsWith(GITHUB_PREFIX)) return url
        return "$base/$url"
    }

    /**
     * 镜像被禁用时给用户看的理由；镜像可用时返回 null（不打扰默认路径）。
     * 开关关闭优先于摘要缺失 —— 先说明「是关掉的」，用户才知道该从哪里打开。
     */
    fun mirrorDisabledReason(digest: String?, mirrorsEnabled: Boolean): String? = when {
        !mirrorsEnabled -> "镜像加速已关闭"
        ResumableDownload.normalizeDigest(digest) == null -> "该版本未提供官方摘要，已禁用镜像"
        else -> null
    }
}
