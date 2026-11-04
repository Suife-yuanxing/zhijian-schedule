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
        /**
         * 这一轮走到哪一步了。
         *
         * 为什么需要它：点「下载更新」之后，真正的字节传输前面还有三件耗时的事——
         * 拉增量清单（一次网络往返）、算基线包 sha256（读 30~50 MB）、探测候选源（每源 256 KiB）。
         * 旧实现把状态一直停在 Idle/Paused 直到这三件做完，用户点下去看到的是一张**完全没变**的卡片，
         * 那几百毫秒到几秒被读成「没点上」。现在点下去立刻落 [DownloadPhase.Preparing]，
         * 卡片当场换成进度形态、按钮当场变成「取消下载」，用户知道自己点中了。
         */
        val phase: DownloadPhase = DownloadPhase.Transfer,
    ) : DownloadState

    /** 用户取消或服务被回收：分片保留，可续传 */
    data class Paused(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : DownloadState

    /** 校验通过、待安装 */
    data class Ready(
        val version: String,
        val file: File,
        val sizeBytes: Long,
    ) : DownloadState

    /** 失败；canResume 为 true 时卡片给「重试」（从分片续传），否则给「重新下载」 */
    data class Failed(
        val version: String,
        val reason: String,
        val canResume: Boolean,
    ) : DownloadState
}

/**
 * 下载的三个阶段。UI 只用来换文案，不影响任何控制流——
 * 判断「能不能取消」「能不能清理缓存」仍然只看 [DownloadState.Running] 本身。
 */
enum class DownloadPhase {
    /** 准备中：拉清单 / 算基线摘要 / 探测下载源，还没有一个字节落盘 */
    Preparing,

    /** 正在传输（含增量拼装） */
    Transfer,

    /** 已下完，正在做最终校验与落盘 */
    Verifying,
}
