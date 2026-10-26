package com.zhijian.schedule.core.update

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
     * 镜像常量表。2026-10-16 实测（PC 侧）：gh-proxy.com / ghproxy.net 可用，其余常见加速站
     * 全部不可达或只回错误页；手机侧此前仅 gh-proxy.com 存活。两个镜像默认关闭，只当直连
     * 停滞/过慢（源监视器判定）时的退路。用户可自行修改本表。
     */
    val MIRRORS: List<UpdateSource> = listOf(
        UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"),
        UpdateSource("ghproxy-net", "镜像 ghproxy.net", "https://ghproxy.net/"),
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
        return (listOf(direct) + mirrors.map { it.copy(url = withPrefix(it.url, directUrl)) })
            .distinctBy { it.url }
    }

    /** 把直连地址挂到镜像前缀后面（`gh-proxy.com/<完整原始 URL>`）；非 GitHub 地址原样返回 */
    fun withPrefix(prefix: String, url: String): String {
        val base = prefix.trimEnd('/')
        if (!url.startsWith(GITHUB_PREFIX)) return url
        return "$base/$url"
    }

    /**
     * 续传时优先上次写入 `.meta` 的源：换源必须清空分片，若探测把顺序改了就会把
     * 已经下了一半的进度丢掉。源不在候选里（例如镜像被移除）则保持探测顺序。
     */
    fun preferResumeSource(sources: List<UpdateSource>, previousUrl: String?): List<UpdateSource> {
        if (previousUrl.isNullOrBlank()) return sources
        val previous = sources.firstOrNull { it.url == previousUrl } ?: return sources
        return listOf(previous) + sources.filter { it.url != previousUrl }
    }

    /**
     * 按探测延迟排序候选源：能连通的排前面（快者优先），探测失败的排最后。
     * 探测失败不代表源永久不可用，所以保留在链尾，而不是直接丢弃。
     * [latenciesMs] 为 null 表示探测失败；全部失败时退回原始顺序。
     */
    fun orderByProbe(
        sources: List<UpdateSource>,
        latenciesMs: Map<String, Long?>,
    ): List<UpdateSource> {
        if (sources.size <= 1) return sources
        val working = sources
            .filter { latenciesMs[it.id] != null }
            .sortedBy { latenciesMs[it.id] ?: Long.MAX_VALUE }
        if (working.isEmpty()) return sources
        val failed = sources.filter { latenciesMs[it.id] == null }
        return working + failed
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
