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
)
