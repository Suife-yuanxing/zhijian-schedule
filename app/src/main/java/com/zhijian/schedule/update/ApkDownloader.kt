package com.zhijian.schedule.update

import android.content.Context
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一次下载请求：设置页构造，前台服务通过 Intent extras 传递 */
data class DownloadRequest(
    val version: String,
    val url: String,
    val sizeBytes: Long?,
    val digest: String?,
)

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

/**
 * 更新下载的唯一事实来源与下载协程体。
 * 状态放在 Singleton 里（UI 与服务共用同一个实例），但**下载协程由前台服务启动**，
 * 所以下载的存活期 = 服务的存活期；用户离开设置页不影响下载。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val client: OkHttpClient,
    private val store: UpdateStore,
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var activeCall: Call? = null

    /**
     * 开始或继续下载：自动读 `.part` 与 `.meta` 决定是否带 `Range`/`If-Range` 续传。
     * 必须在调用方（前台服务）的 scope 中执行。
     */
    suspend fun download(request: DownloadRequest) {
        cancelRequested.set(false)
        val result = withContext(Dispatchers.IO) {
            try {
                runDownload(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (cancelRequested.get()) paused(request.version)
                else DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true)
            } catch (t: Throwable) {
                DownloadState.Failed(request.version, "下载出错（${t.message ?: t::class.simpleName}）", canResume = true)
            }
        }
        _state.value = result
    }

    /** 用户取消或服务被回收：中止网络读取并把状态落到 Paused，分片保留以便续传 */
    fun cancel() {
        cancelRequested.set(true)
        runCatching { activeCall?.cancel() }
        val current = _state.value
        if (current is DownloadState.Running) _state.value = paused(current.version)
    }

    /** 前台服务超时（Android 15+ 的 dataSync 累计上限）：中止并落到 Failed，分片仍然可续传 */
    fun timeout() {
        cancelRequested.set(true)
        runCatching { activeCall?.cancel() }
        val current = _state.value
        if (current is DownloadState.Running) {
            _state.value = DownloadState.Failed(
                current.version,
                "系统结束下载（前台服务超时），可继续下载",
                canResume = true,
            )
        }
    }

    /** 某个版本已落盘的分片字节数 */
    fun downloadedBytes(version: String): Long = store.partBytes(version)

    /** `filesDir/updates/` 当前占用字节数 */
    fun cacheBytes(): Long = store.cacheBytes()

    /** 清空下载缓存，返回删除的文件数 */
    fun clearCache(): Int = store.clearCache()

    /** 应用启动时清理「版本 <= 当前已安装版本」的残留，返回删除的文件数 */
    fun cleanupOutdated(currentVersion: String): Int = store.cleanupOutdated(currentVersion)

    /**
     * 检查到新版本后，用磁盘上已有的东西覆盖状态：已校验的 `.apk` → Ready，未完成的分片 → Paused。
     * 没有这一步的话，进程重启后点「继续下载」会把已经下好的 46 MB 又从头下一遍。下载进行中不覆盖。
     */
    fun restoreFromDisk(version: String, sizeBytes: Long?) {
        if (_state.value is DownloadState.Running) return
        val apk = store.apkFile(version)
        if (apk.isFile && apk.length() > 0L && (sizeBytes == null || apk.length() == sizeBytes)) {
            _state.value = DownloadState.Ready(version, apk, apk.length())
            return
        }
        val bytes = store.partBytes(version)
        if (bytes > 0L) {
            _state.value = DownloadState.Paused(
                version = version,
                downloadedBytes = bytes,
                totalBytes = sizeBytes ?: store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
            )
        }
    }

    // ---------- 下载体 ----------

    private sealed interface Outcome {
        data class Done(val state: DownloadState) : Outcome

        /** 需要丢掉分片、从头再来一次（416 损坏，或 206 的起点与现有分片对不上） */
        data object RetryFromScratch : Outcome
    }

    private fun paused(version: String): DownloadState = DownloadState.Paused(
        version = version,
        downloadedBytes = store.partBytes(version),
        totalBytes = store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
    )

    private suspend fun runDownload(request: DownloadRequest): DownloadState {
        var restarted = false
        while (true) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return paused(request.version)

            val part = store.partFile(request.version)
            val meta = store.readMeta(request.version)
            val partBytes = if (part.isFile) part.length() else 0L
            val knownTotal = request.sizeBytes?.takeIf { it > 0 }
                ?: meta?.totalBytes?.takeIf { it > 0 }

            // 磁盘预检：只按「还差多少」判断，续传时不再要求一整份的空闲空间
            val missing = (knownTotal ?: partBytes) - partBytes
            if (missing > 0 && store.freeSpaceBytes() < missing) {
                return DownloadState.Failed(
                    request.version,
                    "存储空间不足，还需 ${ResumableDownload.formatBytes(missing)}",
                    canResume = false,
                )
            }

            val builder = Request.Builder()
                .url(request.url)
                // 不加这行的话 OkHttp 会透明解压，Content-Length 变成压缩后大小，进度分母就错了
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
            if (partBytes > 0L && meta != null && meta.url == request.url) {
                builder.header("Range", "bytes=$partBytes-")
                // 交给服务端判断「资源没变才给 206」，能挡住资产被替换导致的新旧分片拼接损坏
                meta.etag?.let { builder.header("If-Range", it) }
            }

            val call = client.newCall(builder.build())
            activeCall = call
            val response = try {
                call.execute()
            } catch (e: IOException) {
                activeCall = null
                return if (cancelRequested.get()) paused(request.version)
                else DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true)
            }

            val outcome = try {
                response.use { handle(request, it, part, partBytes, knownTotal) }
            } finally {
                activeCall = null
            }

            when (outcome) {
                is Outcome.Done -> return outcome.state
                Outcome.RetryFromScratch -> {
                    if (restarted) {
                        return DownloadState.Failed(request.version, "下载文件损坏，请重新下载", canResume = false)
                    }
                    restarted = true
                    store.deleteAll(request.version)
                }
            }
        }
    }

    private fun handle(
        request: DownloadRequest,
        response: Response,
        part: File,
        partBytes: Long,
        knownTotal: Long?,
    ): Outcome {
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val decision = ResumableDownload.decideRange(response.code, partBytes, contentRange?.total ?: knownTotal)

        when (decision) {
            is RangeDecision.Fail -> return Outcome.Done(
                DownloadState.Failed(request.version, decision.reason, decision.canResume)
            )
            RangeDecision.AlreadyComplete -> return Outcome.Done(verifyAndFinish(request, part))
            RangeDecision.Corrupt -> return Outcome.RetryFromScratch
            else -> Unit
        }

        // 206 的起点必须与已有分片对齐，否则拼出来的 APK 是坏的
        if (decision is RangeDecision.Append && contentRange != null && contentRange.start != partBytes) {
            return Outcome.RetryFromScratch
        }

        val appendFrom = (decision as? RangeDecision.Append)?.offset ?: 0L
        if (decision is RangeDecision.Restart) {
            // 服务端忽略了 Range，或 If-Range 判定资源已变：丢掉分片从头写这一次响应
            store.deleteMeta(request.version)
            part.delete()
        }

        val body = response.body
            ?: return Outcome.Done(DownloadState.Failed(request.version, "响应无内容，请重试", canResume = true))
        val total = ResumableDownload.resolveTotalBytes(body.contentLength(), contentRange, knownTotal)

        // 先落元数据再写数据：中途被杀也能靠 .meta 续传
        store.writeMeta(
            PartMeta(
                version = request.version,
                url = request.url,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                totalBytes = total ?: 0L,
                digest = request.digest,
            )
        )

        val meter = SpeedMeter()
        var written = appendFrom
        meter.record(System.currentTimeMillis(), written)
        _state.value = DownloadState.Running(request.version, written, total, 0L, null)

        RandomAccessFile(part, "rw").use { file ->
            if (appendFrom == 0L) file.setLength(0L)
            file.seek(appendFrom)
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    if (cancelRequested.get()) return Outcome.Done(paused(request.version))
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    file.write(buffer, 0, read)
                    written += read
                    meter.record(System.currentTimeMillis(), written)
                    _state.value = DownloadState.Running(
                        version = request.version,
                        downloadedBytes = written,
                        totalBytes = total,
                        bytesPerSecond = meter.bytesPerSecond() ?: 0L,
                        etaSeconds = meter.etaSeconds(written, total),
                    )
                }
            }
            file.fd.sync()
        }

        if (total != null && written != total) {
            return Outcome.Done(
                DownloadState.Failed(
                    request.version,
                    "下载未完成（已收到 ${ResumableDownload.formatBytes(written)}），可继续下载",
                    canResume = true,
                )
            )
        }
        return Outcome.Done(verifyAndFinish(request, part))
    }

    /** 校验通过（长度 + 可选哈希）才重命名成 `.apk` 并删除 `.meta`；任何一步不过都不改名 */
    private fun verifyAndFinish(request: DownloadRequest, part: File): DownloadState {
        val expectedBytes = request.sizeBytes?.takeIf { it > 0 }
            ?: store.readMeta(request.version)?.totalBytes?.takeIf { it > 0 }
        return when (ResumableDownload.verifyFile(part, expectedBytes, request.digest)) {
            VerifyResult.Ok -> {
                val apk = store.apkFile(request.version)
                apk.delete()
                if (part.renameTo(apk)) {
                    store.deleteMeta(request.version)
                    DownloadState.Ready(request.version, apk, apk.length())
                } else {
                    store.deleteAll(request.version)
                    DownloadState.Failed(request.version, "安装包保存失败，请重试", canResume = false)
                }
            }
            // 长度不符多半是分片没写完，保留分片让用户续传
            VerifyResult.LengthMismatch -> DownloadState.Failed(
                request.version,
                "下载未完成（已收到 ${ResumableDownload.formatBytes(part.length())}），可继续下载",
                canResume = true,
            )
            VerifyResult.DigestMismatch -> {
                store.deleteAll(request.version)
                DownloadState.Failed(request.version, "安装包校验失败，请重新下载", canResume = false)
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        val USER_AGENT = "ZhiJianSchedule/" + BuildConfig.VERSION_NAME
    }
}
