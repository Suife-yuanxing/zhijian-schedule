package com.zhijian.schedule.core.update

/** 一次下载请求：设置页构造，前台服务通过 Intent extras 传递 */
data class DownloadRequest(
    val version: String,
    /** 直连地址（GitHub `browser_download_url`），同时是镜像候选的模板 */
    val url: String,
    val sizeBytes: Long?,
    val digest: String?,
    /** 设置页的「镜像加速」开关；关闭时候选只有直连 */
    val mirrorsEnabled: Boolean = false,
    /**
     * 增量清单地址（Release 里的 patch.json）；null = 这个版本没有提供增量。
     *
     * 这里只带地址不带内容：清单要不要解析、能不能用，取决于本机基线包对不对得上，
     * 那个判断发生在真正开始下载之后（见 [ApkDownloader.download]）。
     */
    val patchUrl: String? = null,
)
