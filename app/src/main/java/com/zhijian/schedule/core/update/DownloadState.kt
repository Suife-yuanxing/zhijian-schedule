package com.zhijian.schedule.core.update

import java.io.File

/** 更新下载的状态；唯一事实来源是 [ApkDownloader] */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        /** null = 样本不足或没有总长，UI 不显示剩余时间 */
        val etaSeconds: Long?,
        /** 当前源标签；直连时为 null（默认路径不打扰），走镜像时显示 */
        val sourceLabel: String? = null,
    ) : DownloadState

    /** 用户取消或服务被回收：分片保留，可续传 */
    data class Paused(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : DownloadState

    /** 校验通过、待安装 */
    data class Ready(val version: String, val file: File, val sizeBytes: Long) : DownloadState

    /** 失败；canResume 为 true 时卡片给「重试」（从分片续传），否则给「重新下载」 */
    data class Failed(val version: String, val reason: String, val canResume: Boolean) : DownloadState
}
