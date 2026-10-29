package com.zhijian.schedule.core.update

import com.zhijian.schedule.BuildConfig
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 下载与源探测共用的 User-Agent */
internal val USER_AGENT = "ZhiJianSchedule/" + BuildConfig.VERSION_NAME

/** 分片路径与顺序路径共用的读写缓冲 */
internal const val BUFFER_BYTES = 64 * 1024

/** 一次下载尝试到底怎么了 */
internal sealed interface Notice {
    data class Done(
        val state: DownloadState,
    ) : Notice

    /** 用户取消 / 超时：分片保留 */
    data object Paused : Notice

    /** 这个源没跑通（自己的重试已用尽）；还有下一个候选就换源，已是最后一个则按 [Notice.Failed.canResume] 收场 */
    data class Failed(
        val reason: String,
        val canResume: Boolean,
    ) : Notice

    /** 服务端不支持 Range：清空分片，改用单连接顺序路径重下一次 */
    data object Fallback : Notice
}

/**
 * 下载的运行时控制面：一次下载过程中的取消/换源标志、在飞连接、真实进度与进度发布。
 *
 * 唯一的**状态流**留在 [ApkDownloader]（UI 与服务共用那个事实来源），这里只管传输过程中会变的量。
 * 源链、分片并行、单连接降级三条路径共享同一个实例，因此它们的函数都以本类为接收者，
 * 按职责拆在各自的文件里（SourceProbe.kt / SourceWatchdog.kt / ChunkedTransfer.kt /
 * SequentialTransfer.kt）。
 */
internal class DownloadSession(
    val client: OkHttpClient,
    val store: UpdateStore,
    private val onProgress: (DownloadRequest, UpdateSource, Long, Long?, SpeedMeter) -> Unit,
) {
    /** 用户取消 / 服务被回收 */
    val cancelRequested = AtomicBoolean(false)

    /** 源监视器触发的中止：与「用户取消」区分开——取消落 Paused，中止是换下一个源 */
    val sourceAbort = AtomicBoolean(false)

    /** 所有在飞的连接；取消时逐个 cancel。并发调度下会有 8 条同时在飞，所以用并发安全的集合 */
    val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /**
     * 最近一次的真实进度。分片是稀疏写入，`.part` 长度包含空洞、不等于已下字节，
     * 所以源监视器与暂停都改看这个数，而不是去读文件长度。
     */
    @Volatile var publishedBytes: Long = 0L

    /** 开始新一轮下载：只清两个标志，在飞连接由调用方在启动前收干净 */
    fun reset() {
        cancelRequested.set(false)
        sourceAbort.set(false)
    }

    fun cancelActiveCalls() {
        activeCalls.toList().forEach { runCatching { it.cancel() } }
    }

    /** 把真实进度交给 [ApkDownloader] 发布（先记账，再按节流写状态流） */
    fun publish(
        request: DownloadRequest,
        source: UpdateSource,
        downloaded: Long,
        total: Long?,
        meter: SpeedMeter,
    ) {
        onProgress(request, source, downloaded, total, meter)
    }
}
