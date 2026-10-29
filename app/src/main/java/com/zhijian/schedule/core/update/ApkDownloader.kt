package com.zhijian.schedule.core.update

import android.os.SystemClock
import com.zhijian.schedule.di.DownloadClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * 更新下载的唯一事实来源与下载协程体。
 * 状态放在 Singleton 里（UI 与服务共用同一个实例），但**下载协程由前台服务启动**，
 * 所以下载的存活期 = 服务的存活期；用户离开设置页不影响下载。
 *
 * 本文件是门面：公开 API、状态、源链编排与进度发布。传输细节按职责拆开 ——
 * [DownloadSession]（运行时控制面，TransferRuntime.kt）、SourceProbe.kt（下载前源探测）、
 * SourceWatchdog.kt（停滞/过慢判决）、ChunkedTransfer.kt（分片并行）、
 * SequentialTransfer.kt（单连接降级）、DownloadVerification.kt（校验与安装交接）。
 */
@Singleton
class ApkDownloader
    @Inject
    constructor(
        @DownloadClient private val client: OkHttpClient,
        private val store: UpdateStore,
    ) {
        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = _state.asStateFlow()

        /**
         * 传输运行时的控制面。状态流留在门面上：源链、分片与顺序路径共享同一个会话实例，
         * 所以取消/换源标志、在飞连接与真实进度都只有一份。
         */
        private val session =
            DownloadSession(
                client = client,
                store = store,
                onProgress = { request, source, downloaded, total, meter ->
                    publish(request, source, downloaded, total, meter)
                },
            )

        /** 最近一次向 UI 发布进度的时间（elapsedRealtime）：把每个 64 KiB 都想刷一次的高频写入压下来 */
        private var lastPublishAt: Long = 0L

        /**
         * 开始或继续下载：按磁盘现状决定「按分片续传」还是「单连接顺序续传」；
         * 有多个候选源时一次尝试只用一个，失败且还有下一个就换源重试一次。
         * 必须在调用方（前台服务）的 scope 中执行。
         */
        suspend fun download(request: DownloadRequest) {
            session.reset()
            // 源探测、换源可能要好几秒：先把「下载中」摆出来，页面不能在这段时间毫无反应
            val (initialBytes, initialTotal) =
                withContext(Dispatchers.IO) {
                    val meta = store.readMeta(request.version)
                    progressOnDisk(request.version, meta) to
                        (request.sizeBytes?.takeIf { it > 0 } ?: meta?.totalBytes?.takeIf { it > 0 })
                }
            session.publishedBytes = initialBytes
            lastPublishAt = 0L
            _state.value =
                DownloadState.Running(
                    version = request.version,
                    downloadedBytes = initialBytes,
                    totalBytes = initialTotal,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                )
            val sources =
                DownloadSourceSet.availableSources(
                    directUrl = request.url,
                    digest = request.digest,
                    mirrors = if (request.mirrorsEnabled) DownloadSourceSet.MIRRORS else emptyList(),
                )
            try {
                val previousUrl = withContext(Dispatchers.IO) { store.readMeta(request.version)?.url }
                val ordered = withContext(Dispatchers.IO) { session.probeSources(sources, request.sizeBytes) }
                val finalSources = DownloadSourceSet.preferResumeSource(ordered, previousUrl)
                if (previousUrl != null && finalSources.firstOrNull()?.url == previousUrl) {
                    UpdateTrace.log { "续传沿用上次下载源 ${finalSources.first().id}" }
                }
                val result = withContext(Dispatchers.IO) { runSourceChain(request, finalSources) }
                _state.value = result
            } catch (e: CancellationException) {
                // 服务被回收时协程会被取消：把状态落回 Paused（分片保留），再向上传播
                _state.value = paused(request.version)
                throw e
            }
        }

        /** 用户取消或服务被回收：中止网络读取并把状态落到 Paused，分片保留以便续传 */
        fun cancel() {
            session.cancelRequested.set(true)
            session.cancelActiveCalls()
            val current = _state.value
            // 用内存里刚发布的真实进度：稀疏 `.part` 的长度会虚高，不能拿它当已下字节
            if (current is DownloadState.Running) {
                _state.value =
                    DownloadState.Paused(
                        version = current.version,
                        downloadedBytes = current.downloadedBytes,
                        totalBytes = current.totalBytes,
                    )
            }
        }

        /** 这一轮下载是否已经被用户取消；服务据此决定「继续下载」是新起一轮还是重复点按 */
        fun isCancelling(): Boolean = session.cancelRequested.get()

        /** 前台服务超时（Android 15+ 的 dataSync 累计上限）：中止并落到 Failed，分片仍然可续传 */
        fun timeout() {
            session.cancelRequested.set(true)
            session.cancelActiveCalls()
            val current = _state.value
            if (current is DownloadState.Running) {
                _state.value =
                    DownloadState.Failed(
                        current.version,
                        "系统结束下载（前台服务超时），可继续下载",
                        canResume = true,
                    )
            }
        }

        /** 某个版本真正可续传的字节数；稀疏分片看 `.meta` 记账，顺序路径看 `.part` 长度 */
        fun downloadedBytes(version: String): Long = progressOnDisk(version, store.readMeta(version))

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
            _state.value =
                DownloadState.Failed(
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
        fun restoreFromDisk(
            version: String,
            sizeBytes: Long?,
        ) {
            if (_state.value is DownloadState.Running) return
            // 状态属于别的版本时先丢掉，避免把旧版安装包当成新版本提供安装
            val current = _state.value
            val currentVersion =
                when (current) {
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
            val meta = store.readMeta(version)
            val bytes = progressOnDisk(version, meta)
            if (bytes > 0L) {
                _state.value =
                    DownloadState.Paused(
                        version = version,
                        downloadedBytes = bytes,
                        totalBytes = sizeBytes ?: meta?.totalBytes?.takeIf { it > 0 },
                    )
                return
            }
            // 文件与分片都不在：若状态还停在 Ready，说明安装包被外部清掉了，给用户明确的重新下载入口
            val ready = _state.value
            if (ready is DownloadState.Ready) {
                _state.value =
                    DownloadState.Failed(
                        version,
                        "安装包已被清理或损坏，请重新下载",
                        canResume = false,
                    )
            }
        }

        // ---------- 下载体 ----------

        private fun paused(version: String): DownloadState {
            val meta = store.readMeta(version)
            return DownloadState.Paused(
                version = version,
                downloadedBytes = progressOnDisk(version, meta),
                totalBytes = meta?.totalBytes?.takeIf { it > 0 },
            )
        }

        /**
         * 磁盘上可续传的字节数。分片稀疏写时 `.part` 长度包含空洞，只认 `.meta` 的记账；
         * 顺序路径（含 v1 旧元数据）的 `.part` 是连续前缀，长度就是已下字节。
         */
        private fun progressOnDisk(
            version: String,
            meta: PartMeta?,
        ): Long =
            if (meta?.sparseChunks == true) {
                ResumableDownload.progressBytes(meta)
            } else {
                store.partBytes(version)
            }

        /**
         * 依次尝试候选源。一次下载尝试内只用一个源；该源失败、停滞或过慢且还有下一个时
         * 自动换下一个重试一次（对用户表现为「点一次下载」）。
         */
        private suspend fun runSourceChain(
            request: DownloadRequest,
            sources: List<UpdateSource>,
        ): DownloadState {
            for (index in sources.indices) {
                coroutineContext.ensureActive()
                if (session.cancelRequested.get()) return paused(request.version)
                val source = sources[index]
                val hasNext = index != sources.lastIndex
                session.sourceAbort.set(false)
                UpdateTrace.log { "尝试下载源 ${source.id}（${source.url}）" }
                // 换源时磁盘已经清空，监视器只算本源新增的字节；首个源从当前进度起算
                val attemptStartBytes = if (index == 0) session.publishedBytes else 0L
                val notice =
                    session.withSourceWatchdog(hasNext, attemptStartBytes) {
                        session.downloadSource(request, source)
                    }
                // 已定论（下完 / 暂停 / 降级成功）就收工；换源的判定留在循环里
                val settled = settleOrNull(request, source, notice, hasNext, sources.size > 1)
                if (settled != null) return settled
                // 还有下一个候选：换源必须清空分片（设计 §3.3）。两个源的同名文件可能内容不同，
                // 混着拼必然坏，事后只能靠整包 SHA-256 发现，代价是白下 46 MB。
                store.deleteAll(request.version)
            }
            // availableSources 至少返回直连一条，所以这里只是防御性收尾
            return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
        }

        /**
         * 把一次源尝试的结论收敛成「有定论 → 状态」，返回 null 表示还没定论、该换下一个源。
         *
         * 最后一个源失败时还要再分两种收场（见 [lastSourceFailure]）。
         */
        private suspend fun settleOrNull(
            request: DownloadRequest,
            source: UpdateSource,
            notice: Notice,
            hasNext: Boolean,
            multiSource: Boolean,
        ): DownloadState? =
            when (notice) {
                is Notice.Done -> notice.state
                Notice.Paused -> paused(request.version)
                is Notice.Failed -> {
                    UpdateTrace.log { "源 ${source.id} 未跑通：${notice.reason}" }
                    if (hasNext) null else lastSourceFailure(request, notice, multiSource)
                }
                // 服务端不认 Range（设计 §2.4）：分片集合已作废，清掉元数据改用单连接重下一次
                Notice.Fallback ->
                    session
                        .withSourceWatchdog(hasNext, 0L) {
                            session.downloadSequential(request, source, fromScratch = true)
                        }.settle(request.version)
            }

        /**
         * 最后一个源也失败。
         *
         * 镜像开启过（`multiSource`）说明前面的源已经把分片清空，无物可续，按设计 §3.4 清盘并报
         * 「所有下载源都失败」；只有直连时不碰磁盘，保留 [Notice.Failed] 的 `canResume`——分片还在、
         * `.meta.url` 就是刚才这个源，用户点「重试」能接着下，与 v1 行为一致。
         */
        private fun lastSourceFailure(
            request: DownloadRequest,
            notice: Notice.Failed,
            multiSource: Boolean,
        ): DownloadState {
            if (!multiSource) return DownloadState.Failed(request.version, notice.reason, notice.canResume)
            store.deleteAll(request.version)
            return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
        }

        /**
         * 把「单个源的尝试结论」收敛成最终状态。保留 [Notice.Failed] 的 `canResume`，不一律压成 false
         * —— 单源且分片还在时，用户点「重试」是能接着下的。
         * 降级路径（[DownloadSession.downloadSequential]）不可能再返回 [Notice.Fallback]（它进去就是要顺序下完），
         * 真出现只可能是逻辑写错，这里按不可续传的失败收场，不再递归降级。
         */
        private fun Notice.settle(version: String): DownloadState =
            when (this) {
                is Notice.Done -> state
                Notice.Paused -> paused(version)
                is Notice.Failed -> DownloadState.Failed(version, reason, canResume)
                Notice.Fallback -> DownloadState.Failed(version, "服务端不支持分片下载，请重试", canResume = false)
            }

        // ---------- 进度发布 ----------

        private fun publish(
            request: DownloadRequest,
            source: UpdateSource,
            downloaded: Long,
            total: Long?,
            meter: SpeedMeter,
        ) {
            // 真实进度先记账：源监视器与暂停都用它，不受下面的 UI 节流影响
            session.publishedBytes = downloaded
            // 8 个 worker 每 64 KiB 就想刷一次，主线程会被重组打满、页面点不动：
            // 一秒钟最多推 4 次，进度条动画本身有 320ms 补间，看起来仍然是连续的
            val now = SystemClock.elapsedRealtime()
            if (lastPublishAt != 0L && now - lastPublishAt < PUBLISH_MIN_INTERVAL_MS) return
            lastPublishAt = now
            _state.value =
                DownloadState.Running(
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
            /** UI 进度节流：最快 250ms 一次，避免高频状态写入把主线程重组打满 */
            const val PUBLISH_MIN_INTERVAL_MS = 250L
        }
    }
