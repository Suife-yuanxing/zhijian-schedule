package com.zhijian.schedule.core.update

import android.os.SystemClock
import com.zhijian.schedule.di.DownloadClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import okhttp3.Request as OkHttpRequest

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
        /**
         * 基线包 sha256 的记忆。点「下载更新」之后这里直接关系首屏响应：
         * 没有它就每次都要把 30~50 MB 的已安装整包读一遍（几百毫秒到一秒多），
         * 而用户点了按钮后看到的第一帧就是这个等待。见 [BaselineDigestCache]。
         */
        private val baselineDigests = BaselineDigestCache()

        /** 同一份摘要的磁盘旁注：跨进程复用，冷启动后第一次点下载也不必重算 */
        private val baselineNotes = BaselineDigestNoteStore({ store.baselineDir }, Json)
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
         * 增量清单的预热缓存（版本 → 清单）。
         *
         * 为什么需要：清单放在 github.com 上，一次往返实测 7.3 s（移动网络），
         * 而它只是用来回答「这次能不能少下几十 MB」。它挡在第一个字节之前，纯亏。
         * 但**打开更新窗口**那一刻用户正在读版本号与更新说明，那段时间做这件事完全免费。
         * 窗口一打开就预热，点下载时直接命中，判定退化成纯本地计算。
         */
        private val patchCache = java.util.concurrent.ConcurrentHashMap<String, PatchDescriptor>()

        /**
         * 开始或继续下载。
         *
         * 两条路径：
         *  · **增量**：本机留着的基线包与清单对得上、且增量确实更省时，只下几 MB 的 patch.bin
         *    再本地拼装（[tryDelta]）。拼装结果仍要过整包 sha256，对不上就回退全量。
         *  · **全量**：源链 + 分片并行（[downloadFull]）。
         *
         * 分流放在这一层而不是 UI：状态机、磁盘、基线三样都在这里，判断才有一致的依据。
         */
        suspend fun download(request: DownloadRequest) {
            // 重新起表：这一轮从「用户点下去」开始算，日志里的 +Nms 才有意义
            UpdateTrace.reset()
            session.reset()
            val (startBytes, startTotal) =
                withContext(Dispatchers.IO) {
                    val meta = store.readMeta(request.version)
                    progressOnDisk(request.version, meta) to
                        (request.sizeBytes?.takeIf { it > 0 } ?: meta?.totalBytes?.takeIf { it > 0 })
                }
            // 先落「准备中」再干任何耗时的事：下面每一步都要几百毫秒到几秒，
            // 这段时间界面必须已经有反应。已下字节取磁盘记账，续传时进度条不会先掉回 0。
            publishPreparing(request, startBytes, startTotal)
            UpdateTrace.log { "准备态已发布，磁盘可续传 $startBytes 字节" }
            val resumable = startBytes > 0L
            //
            // 从这里开始是「点下载 → 真的在跑」的全部等待。三件事本来可以串着做，
            // 但它们之间没有依赖，串行做等于把等待相加：实测串行 1.66 s（增量判定）
            // + 1.99 s（源探测）= 3.65 s 才发出第一个字节请求。
            // 现在增量判定与源探测**同时起跑**，等待变成两者里较慢的那一个。
            val delta =
                if (resumable || request.patchUrl == null) {
                    null
                } else {
                    // 续传不试增量：增量走的是另一个文件（patch.bin），复用不了已有分片；
                    // 先花一次往返拉 patch.json 只会把「点继续」到「真的在跑」再拉长一截。
                    scope.async(Dispatchers.IO) { resolveDelta(request) }
                }
            val sourcesDeferred = scope.async(Dispatchers.IO) { prepareSources(request, resumable) }
            if (delta != null) {
                // **增量判定带硬超时。** 它省的是流量，不是时间：值得为它多等一点，
                // 但绝不该让用户在「点完按钮、还没有第一个字节」的状态里干等。
                // 实测（2026-11-04，移动网络）：patch.json 落在 github.com 上，
                // 一次往返 7.3 s —— 而它换来的只是「这次能不能少下 28 MB」这个判断题。
                // 超时就放弃增量、直接走全量：多花的是流量，少花的是用户 7 秒。
                val strategy = withTimeoutOrNull(DELTA_DECIDE_TIMEOUT_MS) { delta.await() }
                if (strategy == null && !delta.isCompleted) {
                    UpdateTrace.log { "增量判定超时（${DELTA_DECIDE_TIMEOUT_MS}ms），直接走全量" }
                    delta.cancel()
                }
                if (strategy != null && runDelta(request, strategy)) return
            }
            val sources = sourcesDeferred.await()
            downloadFull(request, sources)
        }

        /** 增量路径的工作协程域：与源探测并行跑，谁先好谁先用 */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 判定这次能不能走增量。只做判定、不下载：判定是「点下载后最慢的那一步」
         * （要读 30~50 MB 的基线包算 sha256），所以它必须能和源探测并行。
         *
         * @return null 表示这次不走增量（没清单 / 基线对不上 / 增量不够省）
         */
        private suspend fun resolveDelta(request: DownloadRequest): DownloadStrategy.Delta? {
            val patchUrl = request.patchUrl ?: return null
            //
            // **先看本地，再走网络。** 增量能不能用有两个前提：清单（网络）与基线包（本地）。
            // 本地这一半几乎免费（读一次目录），而网络那一半是一次 GitHub 往返（实测 1.8 s）。
            // 先查本地：没有基线包时，清单拉回来也一定会被判「没有本地基线，只能全量」，
            // 那 1.8 s 就是纯粹的等待——而它正好落在用户点完按钮、第一个字节之前。
            //
            // 这个顺序还有一个附带好处：首次更新（多数人的多数时候）直接走全量，
            // 一个网络请求都不发，连 patch.json 的流量与限流都省了。
            val baselineVersion =
                withContext(Dispatchers.IO) {
                    store.baselineVersion()?.takeIf { version ->
                        store.baselineFile(version).let { it.isFile && it.length() > 0L }
                    }
                }
            if (baselineVersion == null) {
                UpdateTrace.log { "本地没有基线包，跳过增量判定（不拉清单）" }
                return null
            }
            // 基线的长度与摘要都要先用：摘要那一步是这里最贵的（32 MB 约 1.2 s），
            // 但它与「拉清单」互不依赖，所以两个 deferred 在 download() 里是同时在跑的。
            val baselineFile = withContext(Dispatchers.IO) { store.baselineFile(baselineVersion) }
            val baselineBytes = baselineFile.takeIf { it.isFile }?.length()
            // 预热命中就直接用，没命中才走网络（并受调用方的 1.5 s 上限约束）
            val descriptor = patchCache[request.version] ?: fetchDescriptor(patchUrl) ?: return null
            val strategy =
                DeltaPolicy.decide(
                    descriptor = descriptor,
                    baseApkBytes = baselineBytes,
                    baseSha256 = baselineDigest(baselineVersion),
                    fullSize = request.sizeBytes,
                )
            return (strategy as? DownloadStrategy.Delta).also {
                UpdateTrace.log { "增量判定：" + strategy.reasonText }
            }
        }

        /**
         * 跑增量并收敛结论。返回 true 表示这一轮已经结束（成功或取消），调用方不要再走全量。
         *
         * 「失败也回退全量」：任何一步不对就返回 false，让全量接着跑——
         * 用户看到的是「下载中」，不会因为增量不可用而多一次点击。
         */
        private suspend fun runDelta(
            request: DownloadRequest,
            strategy: DownloadStrategy.Delta,
        ): Boolean {
            UpdateTrace.log {
                "走增量：" + ResumableDownload.formatBytes(strategy.patchSize) +
                    " / 全量 " + ResumableDownload.formatBytes(strategy.fullSize)
            }
            _state.value =
                DownloadState.Running(
                    version = request.version,
                    downloadedBytes = 0L,
                    totalBytes = strategy.patchSize,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                    sourceLabel = "增量 " + ResumableDownload.formatBytes(strategy.patchSize),
                    phase = DownloadPhase.Transfer,
                )
            val outcome =
                withContext(Dispatchers.IO) {
                    session.withSourceWatchdog(hasNextSource = false, attemptStartBytes = 0L) {
                        session.runDeltaUpdate(request, strategy)
                    }
                }
            return when (outcome) {
                is DeltaOutcome.Cancelled -> {
                    _state.value = paused(request.version)
                    true
                }
                is DeltaOutcome.Fallback -> {
                    UpdateTrace.log { "增量回退全量：" + outcome.reason }
                    false
                }
                is DeltaOutcome.Ready -> {
                    _state.value = DownloadState.Ready(request.version, outcome.apk, outcome.sizeBytes)
                    true
                }
            }
        }

        /**
         * 决定这一轮按什么顺序试哪些源。
         *
         * 续传且上次那个源还在候选里：**不探测**，直接接着下——那个源刚刚才把几个 MB
         * 写进分片，它能不能跑已经有答案了，再花一两次往返去证明一遍纯属让用户多等。
         */
        private suspend fun prepareSources(
            request: DownloadRequest,
            resumable: Boolean,
        ): List<UpdateSource> {
            val sources =
                DownloadSourceSet.availableSources(
                    directUrl = request.url,
                    digest = request.digest,
                    mirrors = if (request.mirrorsEnabled) DownloadSourceSet.MIRRORS else emptyList(),
                )
            val meta = withContext(Dispatchers.IO) { store.readMeta(request.version) }
            val previousUrl = meta?.url
            val resumeSource =
                previousUrl?.takeIf { resumable && sources.any { source -> source.url == it } }
            if (resumeSource != null) {
                UpdateTrace.log { "续传跳过源探测，沿用 $resumeSource" }
                return DownloadSourceSet.preferResumeSource(sources, resumeSource)
            }
            val ordered = session.probeSources(sources, request.sizeBytes)
            return DownloadSourceSet.preferResumeSource(ordered, previousUrl)
        }

        /**
         * 落「准备中」状态。字节数取磁盘上的记账而不是 0：
         * 续传时进度条应该停在原处等，而不是先掉回 0 再长上去。
         */
        private fun publishPreparing(
            request: DownloadRequest,
            bytes: Long,
            total: Long?,
        ) {
            session.publishedBytes = bytes
            lastPublishAt = 0L
            _state.value =
                DownloadState.Running(
                    version = request.version,
                    downloadedBytes = bytes,
                    totalBytes = total,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                    phase = DownloadPhase.Preparing,
                )
        }

        /** 拉增量清单；拿不到就当这个版本没有增量 */
        private suspend fun fetchDescriptor(patchUrl: String): PatchDescriptor? =
            runCatching {
                val req =
                    OkHttpRequest
                        .Builder()
                        .url(patchUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val text = response.body?.string().orEmpty()
                    PatchManifest.parse(text, patchUrl)
                }
            }.getOrNull()

        /**
         * 基线包的 sha256；没有基线或读不出来都返回 null（[DeltaPolicy] 会据此回退全量）。
         *
         * 三级加速，逐级省掉重复读盘：
         * 1. 进程内记忆（[BaselineDigestCache]）——同一次运行里点多少次下载都只读一遍；
         * 2. 磁盘旁注（[UpdateStore.readBaselineDigest]）——冷启动后第一次点下载也不必重算；
         * 3. 都没有才真读文件。
         * 这一步实测 1.66 s（32 MB 的包），是「点下载」之后最大的一块等待；
         * 而它算出来的东西只跟文件内容有关，重算没有任何意义。
         */
        private suspend fun baselineDigest(baselineVersion: String?): String? {
            if (baselineVersion == null) return null
            return withContext(Dispatchers.IO) {
                val file = store.baselineFile(baselineVersion)
                if (!file.isFile) return@withContext null
                baselineDigests.get(file)?.let { return@withContext it }
                baselineNotes.read(baselineVersion, file)?.let {
                    baselineDigests.put(file, it)
                    return@withContext it
                }
                runCatching { UpdateDigest.sha256Of(file) }
                    .getOrNull()
                    ?.also {
                        baselineDigests.put(file, it)
                        baselineNotes.write(baselineVersion, file, it)
                    }
            }
        }

        /** 全量路径：源链 + 分片并行（[sources] 由 [prepareSources] 并行备好） */
        private suspend fun downloadFull(
            request: DownloadRequest,
            sources: List<UpdateSource>,
        ) {
            try {
                UpdateTrace.log { "源链就绪：" + sources.joinToString(",") { it.id } }
                val result = withContext(Dispatchers.IO) { runSourceChain(request, sources) }
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
            baselineDigests.clear()
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
         * 把「当前已安装的这一版」留作增量基线。
         *
         * 调用方传入本应用自己的 APK 路径（[Context.getApplicationInfo] 的 sourceDir）。
         * 这一步是增量更新能生效的前提：没有基线包，下次更新只能下全量。
         *
         * 幂等且便宜：基线已存在且大小一致时直接返回，不会每次启动都复制几十 MB。
         * 失败（存储不足等）不抛出——退化成「下次更新走全量」而已，不该影响启动。
         *
         * @return true = 基线已就绪
         */
        fun ensureBaseline(
            installedVersion: String,
            installedApk: java.io.File?,
        ): Boolean {
            if (installedApk == null || !installedApk.isFile) return false
            val existing = store.baselineFile(installedVersion)
            if (existing.isFile && existing.length() == installedApk.length()) return true
            // 只剩一份基线，换版本时旧的自然被清掉（见 UpdateStore.saveBaseline）。
            // 记忆与磁盘旁注一起作废：它们描述的是那个刚被覆盖掉的文件。
            baselineDigests.clear()
            return store.saveBaseline(installedVersion, installedApk)
        }

        /**
         * 预热基线包摘要：把「点下载之后最贵的那一步」搬到启动后的空闲时间。
         *
         * 实测（2026-11-04，PKG110）：32 MB 的基线包算一遍 sha256 要 1.17 s，
         * 而它恰好发生在用户点「下载更新」之后、第一个字节之前——那一段是纯等待，
         * 界面上只有一句「正在准备更新」，最容易读成卡死。
         *
         * 调用时机：「我的」页/启动时 ensureBaseline 之后、且不在下载中。
         * 命中磁盘旁注时这一趟只读几百字节 JSON，代价可以忽略；没命中就顺手补上，
         * 于是用户真正点下载时几乎必然命中。
         *
         * 失败静默：算不出来只是回到「点下载时现算」，不比现在更差，不该打扰任何人。
         */
        suspend fun warmBaselineDigest(version: String) {
            if (_state.value is DownloadState.Running) return
            runCatching { baselineDigest(version) }
        }

        /**
         * 预热增量清单：打开更新窗口时调用，结果留在 [patchCache] 里供点下载时直接用。
         *
         * 与 [warmBaselineDigest] 同理——把「点下载之后才做」的网络往返搬到「用户正在读更新说明」时。
         * 失败静默：拿不到清单只是回到「点下载时现拉（并在超时后走全量）」，不比现在更差。
         */
        fun prefetchPatch(
            version: String,
            patchUrl: String?,
        ) {
            if (patchUrl.isNullOrBlank() || patchCache.containsKey(version)) return
            if (_state.value is DownloadState.Running) return
            scope.launch {
                runCatching {
                    withTimeoutOrNull(PATCH_PREFETCH_TIMEOUT_MS) { fetchDescriptor(patchUrl) }
                }.getOrNull()?.let { patchCache[version] = it }
            }
        }

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
            // 字节已经齐了、但整包 sha256 还要算（几十 MB，几百毫秒）：
            // 这一段必须切到「校验中」，否则进度条卡在 100%、按钮还写着「取消下载」，读作卡死。
            // 顺带跳过节流——这一帧是阶段切换，不能因为落在 250ms 窗口里被吞掉。
            val verifying = total != null && (total <= 0L || downloaded >= total)
            val now = SystemClock.elapsedRealtime()
            if (!verifying && lastPublishAt != 0L && now - lastPublishAt < PUBLISH_MIN_INTERVAL_MS) return
            lastPublishAt = now
            _state.value =
                DownloadState.Running(
                    version = request.version,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    bytesPerSecond = if (verifying) 0L else meter.bytesPerSecond() ?: 0L,
                    etaSeconds = if (verifying) null else meter.etaSeconds(downloaded, total),
                    // 直连时不显示来源，默认路径不打扰
                    sourceLabel = source.label.takeIf { source.id != DownloadSourceSet.DIRECT_ID },
                    phase = if (verifying) DownloadPhase.Verifying else DownloadPhase.Transfer,
                )
        }

        private companion object {
            /** UI 进度节流：最快 250ms 一次，避免高频状态写入把主线程重组打满 */
            const val PUBLISH_MIN_INTERVAL_MS = 250L

            /**
             * 增量判定的等待上限。
             *
             * 判定的产出是「这次能不能少下几十 MB」，它不改善首次可见速度——
             * 而它挡在第一个字节之前。实测 GitHub 往返 7.3 s，用户已经点了按钮，
             * 屏幕上却只有「正在准备更新」。1.5 s 内答不上来就直接全量，
             * 用流量换响应：这条路径第一次更新就走不到增量，本来也没省多少。
             */
            const val DELTA_DECIDE_TIMEOUT_MS = 1_500L

            /**
             * 清单预热的等待上限。
             *
             * 比 [DELTA_DECIDE_TIMEOUT_MS] 宽松：预热发生在用户读更新说明的时候，
             * 早一秒晚一秒都没人感知，而多等一会儿命中率就高得多。
             */
            const val PATCH_PREFETCH_TIMEOUT_MS = 8_000L
        }
    }
