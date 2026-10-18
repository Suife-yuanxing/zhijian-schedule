package com.zhijian.schedule.update

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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

/** 源监视器的判决（纯逻辑，便于单测） */
internal sealed interface WatchdogVerdict {
    data object Ok : WatchdogVerdict

    /** 长时间没有新字节：直连被黑洞、连接假死 */
    data object Stalled : WatchdogVerdict

    /** 还能换源，但有效速度长期低于下限——手机网络里「慢但能下」的体感等同不可用 */
    data object TooSlow : WatchdogVerdict
}

/** 25 秒没有新字节视为停滞 */
internal const val WATCHDOG_STALL_TIMEOUT_MS = 25_000L

/** 前 1 分钟是连接/加速宽限期，之后才评价速度 */
internal const val WATCHDOG_SPEED_GRACE_MS = 60_000L

/** 有效速度下限：低于 30KB/s 且还有候选源时换源（46MB 按此速度要 25 分钟以上） */
internal const val WATCHDOG_MIN_SPEED_BPS = 30_000L

internal fun sourceWatchdogVerdict(
    movedBytes: Long,
    stillForMs: Long,
    elapsedMs: Long,
    hasNextSource: Boolean,
): WatchdogVerdict = when {
    stillForMs >= WATCHDOG_STALL_TIMEOUT_MS -> WatchdogVerdict.Stalled
    hasNextSource && movedBytes > 0L && elapsedMs >= WATCHDOG_SPEED_GRACE_MS &&
        movedBytes * 1000L / elapsedMs.coerceAtLeast(1) < WATCHDOG_MIN_SPEED_BPS -> WatchdogVerdict.TooSlow
    else -> WatchdogVerdict.Ok
}

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

    /** 源监视器触发的中止：与「用户取消」区分开——取消落 Paused，中止是换下一个源 */
    private val sourceAbort = AtomicBoolean(false)

    /** 所有在飞的连接；取消时逐个 cancel。并发调度下会有 8 条同时在飞，所以用并发安全的集合 */
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /**
     * 开始或继续下载：按磁盘现状决定「按分片续传」还是「单连接顺序续传」；
     * 有多个候选源时一次尝试只用一个，失败且还有下一个就换源重试一次。
     * 必须在调用方（前台服务）的 scope 中执行。
     */
    suspend fun download(request: DownloadRequest) {
        cancelRequested.set(false)
        sourceAbort.set(false)
        val sources = DownloadSourceSet.availableSources(
            directUrl = request.url,
            digest = request.digest,
            mirrors = if (request.mirrorsEnabled) DownloadSourceSet.MIRRORS else emptyList(),
        )
        try {
            val previousUrl = withContext(Dispatchers.IO) { store.readMeta(request.version)?.url }
            val ordered = withContext(Dispatchers.IO) { probeSources(sources, request.sizeBytes) }
            val finalSources = DownloadSourceSet.preferResumeSource(ordered, previousUrl)
            if (previousUrl != null && finalSources.firstOrNull()?.url == previousUrl) {
                Log.i(TAG, "续传沿用上次下载源 ${finalSources.first().id}")
            }
            val result = withContext(Dispatchers.IO) { runSourceChain(request, finalSources) }
            _state.value = result
        } catch (e: CancellationException) {
            // 服务被回收时协程会被取消：把状态落回 Paused（分片保留），再向上传播
            _state.value = paused(request.version)
            throw e
        }
    }

    /**
     * 下载前用 1KB Range 请求探测候选源，按延迟排序。
     * 直连被黑洞时不用等源监视器 25s 才换镜像；探测失败的源保留在链尾，仍可作为退路。
     * 同时校验响应长度，避免把「200 的镜像错误页」当成可用源。
     */
    private suspend fun probeSources(
        sources: List<UpdateSource>,
        expectedSize: Long?,
    ): List<UpdateSource> {
        if (sources.size <= 1) return sources
        val probeClient = client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val latencies = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    val started = SystemClock.elapsedRealtime()
                    val ok = runCatching {
                        val request = Request.Builder()
                            .url(source.url)
                            .header("Range", "bytes=0-1023")
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", USER_AGENT)
                            .build()
                        probeClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use false
                            val total = ResumableDownload
                                .parseContentRange(response.header("Content-Range"))?.total
                                ?: response.body?.contentLength()
                            total == null || expectedSize == null || total == expectedSize
                        }
                    }.getOrDefault(false)
                    source.id to if (ok) SystemClock.elapsedRealtime() - started else null
                }
            }.awaitAll().toMap()
        }
        val ordered = DownloadSourceSet.orderByProbe(sources, latencies)
        Log.i(TAG, "源探测延迟(ms) $latencies → ${ordered.map { it.id }}")
        return ordered
    }

    /** 用户取消或服务被回收：中止网络读取并把状态落到 Paused，分片保留以便续传 */
    fun cancel() {
        cancelRequested.set(true)
        cancelActiveCalls()
        val current = _state.value
        if (current is DownloadState.Running) _state.value = paused(current.version)
    }

    /** 前台服务超时（Android 15+ 的 dataSync 累计上限）：中止并落到 Failed，分片仍然可续传 */
    fun timeout() {
        cancelRequested.set(true)
        cancelActiveCalls()
        val current = _state.value
        if (current is DownloadState.Running) {
            _state.value = DownloadState.Failed(
                current.version,
                "系统结束下载（前台服务超时），可继续下载",
                canResume = true,
            )
        }
    }

    private fun cancelActiveCalls() {
        activeCalls.toList().forEach { runCatching { it.cancel() } }
    }

    /** 某个版本已落盘的分片字节数 */
    fun downloadedBytes(version: String): Long = store.partBytes(version)

    /** `filesDir/updates/` 当前占用字节数 */
    fun cacheBytes(): Long = store.cacheBytes()

    /** 清空下载缓存，返回删除的文件数；文件没了，状态也必须退出 Ready/Paused，否则安装永远打不开 */
    fun clearCache(): Int {
        val deleted = store.clearCache()
        if (_state.value !is DownloadState.Running) _state.value = DownloadState.Idle
        return deleted
    }

    /**
     * 安装前核对待安装的 APK 是否仍在、长度是否与校验通过时一致。
     * 系统清理或「清理下载缓存」可能删掉文件，此时把状态落成可重新下载的失败态并返回 false。
     */
    fun validateReady(): Boolean {
        val ready = _state.value as? DownloadState.Ready ?: return true
        if (ready.file.isFile && ready.file.length() == ready.sizeBytes) return true
        ready.file.delete()
        _state.value = DownloadState.Failed(
            ready.version,
            "安装包已被清理或损坏，请重新下载",
            canResume = false,
        )
        return false
    }

    /** 应用启动时清理「版本 <= 当前已安装版本」的残留，返回删除的文件数 */
    fun cleanupOutdated(currentVersion: String): Int = store.cleanupOutdated(currentVersion)

    /**
     * 检查到新版本后，用磁盘上已有的东西覆盖状态：已校验的 `.apk` → Ready，未完成的分片 → Paused。
     * 没有这一步的话，进程重启后点「继续下载」会把已经下好的 46 MB 又从头下一遍。下载进行中不覆盖。
     */
    fun restoreFromDisk(version: String, sizeBytes: Long?) {
        if (_state.value is DownloadState.Running) return
        // 状态属于别的版本时先丢掉，避免把旧版安装包当成新版本提供安装
        val current = _state.value
        val currentVersion = when (current) {
            is DownloadState.Ready -> current.version
            is DownloadState.Paused -> current.version
            else -> null
        }
        if (currentVersion != null && currentVersion != version) {
            _state.value = DownloadState.Idle
        }
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
            return
        }
        // 文件与分片都不在：若状态还停在 Ready，说明安装包被外部清掉了，给用户明确的重新下载入口
        val ready = _state.value
        if (ready is DownloadState.Ready) {
            _state.value = DownloadState.Failed(
                version,
                "安装包已被清理或损坏，请重新下载",
                canResume = false,
            )
        }
    }

    // ---------- 下载体 ----------

    /** 一次下载尝试的说到底怎么了 */
    private sealed interface Notice {
        data class Done(val state: DownloadState) : Notice

        /** 用户取消 / 超时：分片保留 */
        data object Paused : Notice

        /** 这个源没跑通（自己的重试已用尽）；还有下一个候选就换源，已是最后一个则按 [canResume] 收场 */
        data class Failed(val reason: String, val canResume: Boolean) : Notice

        /** 服务端不支持 Range：清空分片，改用单连接顺序路径重下一次 */
        data object Fallback : Notice
    }

    private fun paused(version: String): DownloadState = DownloadState.Paused(
        version = version,
        downloadedBytes = store.partBytes(version),
        totalBytes = store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
    )

    /**
     * 依次尝试候选源。一次下载尝试内只用一个源；该源失败、停滞或过慢且还有下一个时
     * 自动换下一个重试一次（对用户表现为「点一次下载」）。
     */
    private suspend fun runSourceChain(request: DownloadRequest, sources: List<UpdateSource>): DownloadState {
        for (index in sources.indices) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return paused(request.version)
            val source = sources[index]
            val hasNext = index != sources.lastIndex
            sourceAbort.set(false)
            Log.i(TAG, "尝试下载源 ${source.id}（${source.url}）")
            val notice = withSourceWatchdog(request.version, hasNext) { downloadSource(request, source) }
            when (notice) {
                is Notice.Done -> return notice.state
                Notice.Paused -> return paused(request.version)
                // 这个源失败了
                is Notice.Failed -> {
                    Log.i(TAG, "源 ${source.id} 未跑通：${notice.reason}")
                    // 还有下一个候选就换源。换源必须清空分片（设计 §3.3）：两个源的同名文件可能
                    // 内容不同，混着拼必然坏，事后只能靠整包 SHA-256 发现，代价是白下 46 MB。
                    if (hasNext) {
                        store.deleteAll(request.version)
                        continue
                    }
                    // 已是最后一个源。分片还在、且 `.meta.url` 就是刚才这个源，所以续传仍然有效
                    // —— 只有真的换过源（sources.size > 1）才因为上面的清空而无物可续，那时才按
                    // 设计 §3.4 收场。镜像默认关闭时 sources 只有直连，这里的行为与 v1 完全一致。
                    if (sources.size > 1) {
                        store.deleteAll(request.version)
                        return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
                    }
                    return DownloadState.Failed(request.version, notice.reason, notice.canResume)
                }
                // 服务端不认 Range（设计 §2.4）：分片集合已作废，清掉元数据改用单连接重下一次
                Notice.Fallback -> return withSourceWatchdog(request.version, hasNext) {
                    downloadSequential(request, source, fromScratch = true)
                }.settle(request.version)
            }
        }
        // availableSources 至少返回直连一条，所以这里只是防御性收尾
        return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
    }

    /**
     * 用源监视器包住一次源尝试：25 秒没有新字节（直连被黑洞、连接假死）或过了宽限期后
     * 有效速度仍低于 30KB/s 且还有候选源时，中止本源的连接交给源链。
     * 「慢 ≠ 失败」仅指首个候选还能稳定产出；持续爬行在手机网络里体感等同不可用。
     */
    private suspend fun <T> withSourceWatchdog(
        version: String,
        hasNextSource: Boolean,
        block: suspend () -> T,
    ): T = coroutineScope {
        val watchdog = launch(Dispatchers.IO) {
            val attemptStartBytes = store.partBytes(version)
            var lastBytes = attemptStartBytes
            val startAt = SystemClock.elapsedRealtime()
            var lastMoveAt = startAt
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val nowMs = SystemClock.elapsedRealtime()
                val bytes = store.partBytes(version)
                if (bytes != lastBytes) {
                    lastBytes = bytes
                    lastMoveAt = nowMs
                }
                when (sourceWatchdogVerdict(
                    movedBytes = bytes - attemptStartBytes,
                    stillForMs = nowMs - lastMoveAt,
                    elapsedMs = nowMs - startAt,
                    hasNextSource = hasNextSource,
                )) {
                    WatchdogVerdict.Ok -> Unit
                    WatchdogVerdict.Stalled -> {
                        Log.i(TAG, "源停滞 ${(nowMs - lastMoveAt) / 1000}s 无新字节，中止换源")
                        sourceAbort.set(true)
                        cancelActiveCalls()
                        break
                    }
                    WatchdogVerdict.TooSlow -> {
                        Log.i(
                            TAG,
                            "源过慢（${bytes - attemptStartBytes}B / ${(nowMs - startAt) / 1000}s），中止换源",
                        )
                        sourceAbort.set(true)
                        cancelActiveCalls()
                        break
                    }
                }
            }
        }
        try {
            block()
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * 把「单个源的尝试结论」收敛成最终状态。保留 [Notice.Failed] 的 `canResume`，不一律压成 false
     * —— 单源且分片还在时，用户点「重试」是能接着下的。
     * 降级路径（[downloadSequential]）不可能再返回 [Notice.Fallback]（它进去就是要顺序下完），
     * 真出现只可能是逻辑写错，这里按不可续传的失败收场，不再递归降级。
     */
    private fun Notice.settle(version: String): DownloadState = when (this) {
        is Notice.Done -> state
        Notice.Paused -> paused(version)
        is Notice.Failed -> DownloadState.Failed(version, reason, canResume)
        Notice.Fallback -> DownloadState.Failed(version, "服务端不支持分片下载，请重试", canResume = false)
    }

    /** 用单个源下载：按磁盘现状选「分片续传」或「顺序续传」，分片进度唯一权威是 `.meta.completedChunks` */
    private suspend fun downloadSource(request: DownloadRequest, source: UpdateSource): Notice {
        val meta = store.readMeta(request.version)
        val partBytes = store.partBytes(request.version)
        val knownTotal = request.sizeBytes?.takeIf { it > 0 } ?: meta?.totalBytes?.takeIf { it > 0 }
        val resume = ResumableDownload.resolveResumePoint(
            version = request.version,
            url = source.url,
            totalBytes = knownTotal,
            partBytes = partBytes,
            rawMeta = meta,
        )

        // 续传要的只有「缺的那部分」，不再要求一整份的空闲空间（设计 §2.6）
        val missing = when (resume) {
            is ResumePoint.Chunked -> resume.plan.totalBytes - resume.completed.sumOf { resume.plan.bytesOf(it) }
            is ResumePoint.Fresh -> knownTotal ?: 0L
        }
        if (missing > 0L && store.freeSpaceBytes() < missing) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "存储空间不足，还需 ${ResumableDownload.formatBytes(missing)}",
                    canResume = false,
                )
            )
        }

        return when (resume) {
            is ResumePoint.Fresh -> {
                // 来源变了或压根没有断点：清掉已下的字节与元数据，两个源的同名文件不能混着拼
                store.deleteAll(request.version)
                val plan = resume.plan ?: return downloadSequential(request, source, fromScratch = true)
                // 只有一个分片：多开连接只会被服务端限速，不如走单连接
                if (plan.chunkCount <= 1) return downloadSequential(request, source, fromScratch = true)
                // 先把「这次是按哪个源、哪个分片大小在拼」锚进 `.meta`：中途被杀时下次才对得上。
                // 先建空文件再写元数据、且 etag/lastModified 一律留空 —— 两个细节都是为了「已经
                // 没有断点了、这次真的要从 0 重来」：旧元数据里那对校验器属于上一次会话，照抄会让
                // If-Range 拿过期校验器去问服务端。真正的校验器由第一片写完时从响应头捕获（见 writeChunk）。
                store.deleteChunks(request.version)
                store.writeMeta(
                    PartMeta(
                        version = request.version,
                        url = source.url,
                        totalBytes = plan.totalBytes,
                        digest = request.digest,
                        chunkSize = plan.chunkSize,
                    )
                )
                parallelDownload(request, source, plan, emptyList())
            }
            is ResumePoint.Chunked -> {
                val plan = resume.plan
                // 续传这一支保留旧元数据里的校验器：`.meta.url` 与当前源一致，说明这确实还是同一份文件
                store.writeMeta(
                    PartMeta(
                        version = request.version,
                        url = source.url,
                        etag = meta?.etag,
                        lastModified = meta?.lastModified,
                        totalBytes = plan.totalBytes,
                        digest = request.digest,
                        chunkSize = plan.chunkSize,
                        completedChunks = resume.completed,
                    )
                )
                parallelDownload(request, source, plan, resume.completed)
            }
        }
    }

    /** 分片并行调度的结果 */
    private sealed interface ChunkOutcome {
        /** 队列被抢空 */
        data object Completed : ChunkOutcome

        /** 某个 worker 发现服务端忽略 Range */
        data object Unsupported : ChunkOutcome

        /** 用户取消 / 超时 */
        data object Cancelled : ChunkOutcome

        /** 源监视器中止（停滞 / 过慢）：交给源链换下一个源 */
        data object Aborted : ChunkOutcome

        data class Error(val reason: String) : ChunkOutcome
    }

    /**
     * `min(8, chunkCount)` 个 worker 抢同一个分片队列，各写自己那一段。**不开分片文件、不需要事后拼接**：
     * 所有 worker 共用同一个 `RandomAccessFile`，`seek` 到各自分片起点后顺序写。
     * 每片写完的顺序是「`fd.sync()` → 记账 → 重写 `.meta`」：先落盘再记账，崩溃后才不会把没写完的片当成完成片。
     */
    private suspend fun parallelDownload(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        completed: List<Int>,
    ): Notice {
        val part = store.partFile(request.version)
        val done = completed.toMutableList()
        val written = LongArray(plan.chunkCount)
        // 续传时已完成的片按满长度记账，进度才不会从更小的数字开始
        completed.forEach { index -> written[index] = plan.bytesOf(index) }
        val lock = Any()
        val meter = SpeedMeter()
        val initial = ChunkProgress(plan, done.sorted(), written).downloadedBytes
        meter.record(System.currentTimeMillis(), initial)
        publish(request, source, initial, plan.totalBytes, meter)

        val outcome = coroutineScope {
            RandomAccessFile(part, "rw").use { file ->
                if (completed.isEmpty()) file.setLength(0L)
                val queue = kotlinx.coroutines.channels.Channel<Int>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED
                )
                (0 until plan.chunkCount).filter { it !in completed }.forEach { queue.trySend(it) }
                queue.close()

                val workers = List(minOf(MAX_CONNECTIONS, plan.chunkCount)) {
                    // 显式钉住 async 的返回类型，且循环用 break 收尾：协程体的末表达式必须是
                    // ChunkOutcome，否则下面按 ChunkOutcome 分派的 when 会因类型是 Any 而不再穷尽
                    async<ChunkOutcome>(Dispatchers.IO) {
                        try {
                            while (true) {
                                coroutineContext.ensureActive()
                                if (cancelRequested.get()) return@async ChunkOutcome.Cancelled
                                if (sourceAbort.get()) return@async ChunkOutcome.Aborted
                                val index = queue.tryReceive().getOrNull() ?: break
                                val result = downloadChunk(request, source, plan, index, file, written, plan.chunkCount, done, lock, meter)
                                // 某片发现服务端不认 Range 就没必要把剩下的片也拉下来，立刻收手
                                if (result !is ChunkOutcome.Completed) return@async result
                            }
                            // 队列被抢空：这个 worker 正常收工
                            ChunkOutcome.Completed
                        } catch (e: Exception) {
                            // 防御纵深：writeChunk 已按裁决兜住读取期 IOException，这里是最后一道闸 ——
                            // 任何漏网异常（close 期 IO、分派竞态）都不允许穿出 async 炸掉 coroutineScope
                            // 把前台服务拖闪退（真机回归 2026-10-16）。CancellationException 必须原样上抛，
                            // 否则结构化并发的取消语义被破坏。
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            if (cancelRequested.get()) ChunkOutcome.Cancelled
                            else ChunkOutcome.Error("网络中断（${e.message ?: e::class.simpleName ?: "IO 错误"}），可继续下载")
                        }
                    }
                }
                // awaitAll 让所有 worker 收尾；只要有一个非 Completed 就取它
                val results = workers.awaitAll()
                results.firstOrNull { it !is ChunkOutcome.Completed } ?: ChunkOutcome.Completed
            }
        }

        return when (outcome) {
            ChunkOutcome.Completed -> verifyAndClearChunks(request, part, plan.totalBytes)
            ChunkOutcome.Unsupported -> Notice.Fallback
            ChunkOutcome.Cancelled -> Notice.Paused
            // 源监视器判了停滞/过慢：本次尝试作废，交给源链。分片仍在，单源收场时还能续传
            ChunkOutcome.Aborted -> Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
            // 片内重试 3 次仍失败（设计 §2.2）：这是「这个源没跑通」，交给源链决定是换下一个源
            // 还是收场。已完成的片留在磁盘上，单源时 canResume = true 才有东西可续。
            is ChunkOutcome.Error -> Notice.Failed(outcome.reason, canResume = true)
        }
    }

    /** 下载一个分片：200 触发降级、416 触发完成/损坏、其余失败重试 3 次（线性退避） */
    private suspend fun downloadChunk(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        written: LongArray,
        chunkCount: Int,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
    ): ChunkOutcome {
        val chunk = plan.chunks[index]
        var attempt = 0
        while (attempt < MAX_CHUNK_ATTEMPTS) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return ChunkOutcome.Cancelled
            if (sourceAbort.get()) return ChunkOutcome.Aborted
            attempt++

            val etag = store.readMeta(request.version)?.etag
            val call = client.newCall(chunkRequest(source, chunk, etag))
            activeCalls.add(call)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                activeCalls.remove(call)
                if (cancelRequested.get()) return ChunkOutcome.Cancelled
                if (sourceAbort.get()) return ChunkOutcome.Aborted
                if (attempt >= MAX_CHUNK_ATTEMPTS) {
                    return ChunkOutcome.Error("网络中断（${e.message ?: "IO 错误"}），可继续下载")
                }
                delay(RETRY_BASE_MILLIS * attempt)
                continue
            }

            val settled = try {
                response.use {
                    applyChunkResponse(request, source, plan, index, file, it, written, chunkCount, done, lock, meter)
                }
            } finally {
                activeCalls.remove(call)
            }

            when (settled) {
                Stop.None -> return ChunkOutcome.Completed
                Stop.Unsupported -> return ChunkOutcome.Unsupported
                Stop.Cancelled -> return ChunkOutcome.Cancelled
                Stop.Abort -> return ChunkOutcome.Aborted
                // 416 说明服务端认为整份都在了，直接交校验去判断
                is Stop.AlreadyComplete -> return ChunkOutcome.Completed
                is Stop.Corrupt -> return ChunkOutcome.Error(settled.reason)
                is Stop.Fail -> return ChunkOutcome.Error(settled.reason)
                is Stop.Retry -> {
                    if (sourceAbort.get()) return ChunkOutcome.Aborted
                    if (attempt >= MAX_CHUNK_ATTEMPTS) {
                        return ChunkOutcome.Error("下载失败（HTTP ${settled.code}），可继续下载")
                    }
                }
            }
            delay(RETRY_BASE_MILLIS * attempt)
        }
        return ChunkOutcome.Error("分片下载失败，可继续下载")
    }

    /** 分片请求；`Accept-Encoding: identity` 不能少，否则 Content-Length 是压缩后大小，长度校验会全错 */
    private fun chunkRequest(source: UpdateSource, chunk: Chunk, etag: String?): Request {
        val builder = Request.Builder()
            .url(source.url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=${chunk.start}-${chunk.endInclusive}")
        // 交给服务端判断「资源没变才给 206」，能挡住资产被替换导致的新旧分片拼接损坏
        etag?.let { builder.header("If-Range", it) }
        return builder.build()
    }

    /** 单次分片响应的处理结论 */
    private sealed interface Stop {
        /** 这一片写完了 */
        data object None : Stop

        /** 服务端忽略 Range（200） */
        data object Unsupported : Stop

        /** 用户取消 / 超时 */
        data object Cancelled : Stop

        /** 源监视器中止本源的连接（停滞 / 过慢） */
        data object Abort : Stop

        /** 416：服务端认为整份文件都在了，交校验判断 */
        data class AlreadyComplete(val total: Long) : Stop

        /** 分片集合坏了：起点对不上或没写完的 416 */
        data class Corrupt(val reason: String) : Stop

        /** 不可重试的失败 */
        data class Fail(val reason: String) : Stop

        /** 可重试的状态码（5xx / 429 / 响应无 body） */
        data class Retry(val code: Int) : Stop
    }

    private fun applyChunkResponse(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        response: Response,
        written: LongArray,
        chunkCount: Int,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
    ): Stop {
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val total = contentRange?.total ?: plan.totalBytes
        val support = ResumableDownload.decideRangeSupport(response.code, chunkCount, done.size)
        return when (support) {
            RangeSupport.Unsupported -> Stop.Unsupported
            RangeSupport.AlreadyComplete -> Stop.AlreadyComplete(total)
            RangeSupport.Corrupt -> Stop.Corrupt("分片集合损坏，请重新下载")
            // 500 与 IO 不降级：那是网络问题，重试即可；4xx 是没救了，直接换下一个源
            is RangeSupport.Fail -> if (support.canResume) {
                Stop.Retry(response.code)
            } else {
                Stop.Fail(support.reason)
            }
            RangeSupport.Supported -> {
                // 206 的起点必须就是这一片的起点，否则拼出来的 APK 是坏的
                if (contentRange != null && contentRange.start != plan.chunks[index].start) {
                    return Stop.Corrupt("分片起点不匹配，请重新下载")
                }
                val body = response.body ?: return Stop.Retry(response.code)
                body.byteStream().use { input ->
                    writeChunk(request, source, plan, index, file, input, response, written, done, lock, meter, total)
                }
            }
        }
    }

    /** 把这一片写进 `.part`；每 64 KiB 刷新一次进度。片没读完（连接被掐）当作这一片失败重试 */
    private fun writeChunk(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        input: java.io.InputStream,
        response: Response,
        written: LongArray,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
        total: Long,
    ): Stop {
        val chunk = plan.chunks[index]
        val buffer = ByteArray(BUFFER_BYTES)
        var offset = chunk.start
        var sinceUpdate = 0L
        // 读取循环必须兜 IOException：用户取消时 OkHttp 会把 HTTP/2 流重置
        // （StreamResetException，IOException 子类）从阻塞中的 read() 抛出；不接住的话
        // 异常穿出 async worker → coroutineScope 连锁取消 → 前台服务崩 → 应用闪退
        // （真机回归 2026-10-16，见 ResumableDownload.resolveReadFailure 的裁决说明）。
        try {
            while (offset <= chunk.endInclusive) {
                if (cancelRequested.get()) return Stop.Cancelled
                if (sourceAbort.get()) return Stop.Abort
                val want = minOf(BUFFER_BYTES.toLong(), chunk.endInclusive - offset + 1L).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) break
                if (read == 0) continue
                // seek + write 必须原子：8 个 worker 共用同一个 RandomAccessFile，文件指针是共享状态。
                // 不锁的话「A 先 seek、被抢占、B 又 seek、A 再 write」会把 A 的数据写到 B 的偏移上，
                // 拼出来的 APK 静默损坏（长度对得上，只有整包 SHA-256 能发现）。锁与进度记账共用同一把。
                synchronized(lock) {
                    file.seek(offset)
                    file.write(buffer, 0, read)
                }
                offset += read
                sinceUpdate += read
                if (sinceUpdate >= PROGRESS_STEP_BYTES) {
                    sinceUpdate = 0L
                    synchronized(lock) {
                        written[index] = offset - chunk.start
                        val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
                        meter.record(System.currentTimeMillis(), now)
                        publish(request, source, now, total, meter)
                    }
                }
            }
        } catch (e: IOException) {
            // 源监视器中止时连接的取消同样会以 IO 异常形式抛出，优先按「换源」裁决
            if (sourceAbort.get()) return Stop.Abort
            when (val verdict = ResumableDownload.resolveReadFailure(e, cancelRequested.get())) {
                is ReadVerdict.StopCancelled -> return Stop.Cancelled
                is ReadVerdict.Retry -> {
                    // 片没读完：交给上层按片重试，这一片下次会被整片覆盖
                    return Stop.Retry(0)
                }
                is ReadVerdict.Rethrow -> throw e
            }
        }
        // 片没读完（连接被掐）：交给上层重试，这一片下次会被整片覆盖
        if (offset <= chunk.endInclusive) return Stop.Retry(0)

        file.fd.sync()
        synchronized(lock) {
            written[index] = plan.bytesOf(index)
            if (index !in done) done.add(index)
            // 校验器：先从响应头捕获（第一片写完时把真正的 ETag/Last-Modified 记下来，
            // 后续片的 If-Range 才拿得到有效值），再退回元数据里已有的值
            val etag = response.header("ETag") ?: store.readMeta(request.version)?.etag
            val lastModified = response.header("Last-Modified") ?: store.readMeta(request.version)?.lastModified
            store.writeMeta(
                PartMeta(
                    version = request.version,
                    url = source.url,
                    etag = etag,
                    lastModified = lastModified,
                    totalBytes = total,
                    digest = request.digest,
                    chunkSize = plan.chunkSize,
                    completedChunks = done.sorted(),
                )
            )
            val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
            meter.record(System.currentTimeMillis(), now)
            publish(request, source, now, total, meter)
        }
        return Stop.None
    }

    /** 全部片齐了才校验；长度不符保留分片（下次只补缺的片），摘要不符必须整份重下 */
    private fun verifyAndClearChunks(request: DownloadRequest, part: File, total: Long): Notice {
        val expectedBytes = request.sizeBytes?.takeIf { it > 0 } ?: total
        return when (ResumableDownload.verifyFile(part, expectedBytes, request.digest)) {
            VerifyResult.Ok -> {
                val apk = store.apkFile(request.version)
                apk.delete()
                if (part.renameTo(apk)) {
                    store.deleteMeta(request.version)
                    Notice.Done(DownloadState.Ready(request.version, apk, apk.length()))
                } else {
                    store.deleteAll(request.version)
                    Notice.Done(DownloadState.Failed(request.version, "安装包保存失败，请重试", canResume = false))
                }
            }
            // 长度不符多半是还有片没写完，保留分片让用户续传
            VerifyResult.LengthMismatch -> Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "下载未完成（已收到 ${ResumableDownload.formatBytes(part.length())}），可继续下载",
                    canResume = true,
                )
            )
            VerifyResult.DigestMismatch -> {
                store.deleteAll(request.version)
                Notice.Done(DownloadState.Failed(request.version, "安装包校验失败，请重新下载", canResume = false))
            }
        }
    }

    // ---------- 单连接顺序路径（v1，保留为 Range 不可用时的降级路径） ----------

    /**
     * 服务端不支持 Range，或只有一两个分片时：回到 v1 已验证过的单连接顺序下载。
     * [fromScratch] 为 true 时必须清掉元数据，否则顺序路径会读着指向镜像的 `.meta` 判续传，
     * 把带 Range 的请求打在镜像上；为 false（v1 元数据 + 同源前缀）时保留元数据，让顺序路径正常追加。
     */
    private suspend fun downloadSequential(
        request: DownloadRequest,
        source: UpdateSource,
        fromScratch: Boolean,
    ): Notice {
        if (fromScratch) store.deleteMeta(request.version)
        val part = store.partFile(request.version)
        val builder = Request.Builder()
            .url(source.url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
        // 顺序续传时带上 Range/If-Range，语义与 v1 完全一致
        if (!fromScratch) {
            val meta = store.readMeta(request.version)
            val partBytes = store.partBytes(request.version)
            if (partBytes > 0L && meta != null && meta.url == source.url) {
                builder.header("Range", "bytes=$partBytes-")
                meta.etag?.let { builder.header("If-Range", it) }
            }
        }
        val call = client.newCall(builder.build())
        activeCalls.add(call)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            activeCalls.remove(call)
            return when {
                cancelRequested.get() -> Notice.Paused
                sourceAbort.get() -> Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
                else -> Notice.Done(
                    DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true)
                )
            }
        }
        return try {
            response.use { handleSequential(request, source, it, part, fromScratch) }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** v1 的下载体：从偏移处顺序写到底，中途被杀靠 `.meta` 的 totalBytes 兜进度分母 */
    private fun handleSequential(
        request: DownloadRequest,
        source: UpdateSource,
        response: Response,
        part: File,
        fromScratch: Boolean,
    ): Notice {
        if (response.code !in 200..299) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "下载失败（HTTP ${response.code}），请重试",
                    canResume = response.code in 500..599,
                )
            )
        }
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val partBytes = if (fromScratch) 0L else store.partBytes(request.version)
        val appendFrom = when {
            fromScratch -> 0L
            // 服务端不认 Range、或 If-Range 判定资源已变：这次响应是整份，从头覆盖
            response.code == 200 -> 0L
            contentRange != null && contentRange.start == partBytes -> partBytes
            else -> 0L
        }
        val body = response.body
            ?: return Notice.Done(DownloadState.Failed(request.version, "响应无内容，请重试", canResume = true))
        val total = ResumableDownload.resolveTotalBytes(body.contentLength(), contentRange, request.sizeBytes)

        // 先落元数据再写数据：中途被杀也能靠 .meta 记住进度分母
        store.writeMeta(
            PartMeta(
                version = request.version,
                url = source.url,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                totalBytes = total ?: 0L,
                digest = request.digest,
            )
        )

        val meter = SpeedMeter()
        var written = appendFrom
        meter.record(System.currentTimeMillis(), written)
        publish(request, source, written, total, meter)

        RandomAccessFile(part, "rw").use { file ->
            if (appendFrom == 0L) file.setLength(0L)
            file.seek(appendFrom)
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    if (cancelRequested.get()) return Notice.Paused
                    if (sourceAbort.get()) return Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    file.write(buffer, 0, read)
                    written += read
                    meter.record(System.currentTimeMillis(), written)
                    publish(request, source, written, total, meter)
                }
            }
            file.fd.sync()
        }

        if (total != null && written != total) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "下载未完成（已收到 ${ResumableDownload.formatBytes(written)}），可继续下载",
                    canResume = true,
                )
            )
        }
        return verifyAndClearChunks(request, part, total ?: part.length())
    }

    // ---------- 进度发布 ----------

    private fun publish(
        request: DownloadRequest,
        source: UpdateSource,
        downloaded: Long,
        total: Long?,
        meter: SpeedMeter,
    ) {
        _state.value = DownloadState.Running(
            version = request.version,
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = meter.bytesPerSecond() ?: 0L,
            etaSeconds = meter.etaSeconds(downloaded, total),
            // 直连时不显示来源，默认路径不打扰
            sourceLabel = source.label.takeIf { source.id != DownloadSourceSet.DIRECT_ID },
        )
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** 实测 8 并发把单连接吞吐提到 5.89 倍；单个常量，日后好调 */
        const val MAX_CONNECTIONS = 8
        const val MAX_CHUNK_ATTEMPTS = 3
        const val RETRY_BASE_MILLIS = 500L
        const val PROGRESS_STEP_BYTES = 64L * 1024L
        const val TAG = "UpdateDownload"

        /** 源监视器巡检间隔 */
        const val WATCHDOG_TICK_MS = 3_000L

        /** 下载前源探测超时：超过这个时间就当该源不可达 */
        const val PROBE_TIMEOUT_MS = 5_000L
        val USER_AGENT = "ZhiJianSchedule/" + BuildConfig.VERSION_NAME
    }
}
