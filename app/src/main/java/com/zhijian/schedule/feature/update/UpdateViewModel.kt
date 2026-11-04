package com.zhijian.schedule.feature.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.update.ApkDownloadService
import com.zhijian.schedule.core.update.ApkDownloader
import com.zhijian.schedule.core.update.ApkInstaller
import com.zhijian.schedule.core.update.DownloadRequest
import com.zhijian.schedule.core.update.DownloadState
import com.zhijian.schedule.core.update.ResumableDownload
import com.zhijian.schedule.core.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UpdateUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val updateInfo: UpdateChecker.UpdateInfo? = null,
    val updateChecked: Boolean = false,
    /** 检查更新的失败原因；非空时状态行显示它 */
    val updateError: String? = null,
    /** 下载状态（唯一事实来源在 ApkDownloader） */
    val download: DownloadState = DownloadState.Idle,
    /** `filesDir/updates/` 当前占用字节数；> 0 时出现「清理下载缓存」 */
    val updateCacheBytes: Long = 0L,
    /** 镜像加速开关（持久化在 SettingsStore，仅透传给下载服务） */
    val mirrorsEnabled: Boolean = true,
    /**
     * 「已经点了下载，但下载状态还没到」的那一小段。
     *
     * 为什么需要它：点按钮 → 启动前台服务 → 服务注入依赖 → 跑到 download() 落 Preparing，
     * 中间隔着一次进程内服务启动。这几十到一百多毫秒里 [download] 还是旧值（Idle / Paused），
     * 卡片和按钮**一点变化都没有**，用户会以为没点中而再点一次。
     * 有了这个本地标记，点下去的同一帧按钮就变成「取消下载」。
     * 它只是视图态，下载的真实状态仍然只认 [DownloadState]。
     */
    val launching: Boolean = false,
) {
    /**
     * 界面上「正在下载」的判定。
     *
     * 不能只看 [download]：点下去到服务落 Preparing 之间有一小段空窗（见 [launching]），
     * 那一段若不认，按钮会在用户点完的瞬间还写着「下载更新」。
     */
    val downloading: Boolean
        get() = launching || download is DownloadState.Running
}

/** 检查更新独立页的状态与动作；更新检查/下载的机器都挂在 Singleton 上，本 VM 只做透传与聚合 */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val updateChecker: UpdateChecker,
        private val downloader: ApkDownloader,
        private val apkInstaller: ApkInstaller,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        var uiState by mutableStateOf(UpdateUiState())
            private set

        /** 每个版本只自动切一次镜像，避免「直连失败→镜像失败→再重试」死循环 */
        private var autoMirrorRetriedVersion: String? = null

        init {
            viewModelScope.launch {
                repository.settings.collect { settings ->
                    uiState = uiState.copy(mirrorsEnabled = settings.mirrorsEnabled)
                }
            }
            viewModelScope.launch {
                downloader.state.collect { state ->
                    // 服务已经把状态接过去了，本地那个「正在启动」的标记就可以撤了
                    val launching = uiState.launching && state is DownloadState.Idle
                    uiState = uiState.copy(download = state, launching = launching)
                    maybeAutoEnableMirrors(state)
                    // 只在终态重算占盘，避免下载中每个分片都去列目录
                    if (state !is DownloadState.Running) refreshUpdateCache()
                }
            }
            // 启动自动检查刚出过结果就直接展示；否则打开窗口即自动检查一次
            val cached = updateChecker.cachedCheck()
            if (cached != null) {
                uiState = uiState.copy(updateChecked = true, updateInfo = cached.info)
                val info = cached.info
                if (info?.apkUrl != null) {
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) { downloader.restoreFromDisk(info.version, info.apkSize) }
                        refreshUpdateCache()
                    }
                    warmUp(info)
                }
            } else {
                checkUpdate()
            }
        }

        private suspend fun refreshUpdateCache() {
            val bytes = withContext(Dispatchers.IO) { downloader.cacheBytes() }
            uiState = uiState.copy(updateCacheBytes = bytes)
        }

        /** 窗口每次打开时校正一次：系统清理可能删掉待安装的 APK，而状态还留在内存里 */
        fun refreshFromDisk() {
            val info = uiState.updateInfo ?: return
            if (info.apkUrl == null) return
            viewModelScope.launch {
                withContext(Dispatchers.IO) { downloader.restoreFromDisk(info.version, info.apkSize) }
                refreshUpdateCache()
            }
        }

        fun checkUpdate() {
            viewModelScope.launch {
                uiState = uiState.copy(busy = true, updateChecked = false, updateError = null)
                val result = updateChecker.check()
                val info = result.getOrNull()
                uiState =
                    uiState.copy(
                        busy = false,
                        updateChecked = true,
                        updateInfo = info,
                        updateError =
                            result
                                .exceptionOrNull()
                                ?.let { it.message ?: it::class.simpleName ?: "检查更新失败" },
                        message = null,
                    )
                // 磁盘上要是已经有下好的安装包或断点，直接把状态恢复出来，别又下一遍
                if (info?.apkUrl != null) {
                    val version = info.version
                    val size = info.apkSize
                    withContext(Dispatchers.IO) { downloader.restoreFromDisk(version, size) }
                    // 检查刚出结果：用户接下来多半会点下载，趁现在预热那两件耗时的事
                    warmUp(info)
                }
                refreshUpdateCache()
            }
        }

        /**
         * 直连失败且镜像关闭时自动开启镜像并重试一次。
         * 用户的设备上镜像开关可能来自旧版本的关闭状态；不这样做的话「直连下载不可用」
         * 会一直复现，用户还得自己发现下面那个开关。
         */
        private fun maybeAutoEnableMirrors(state: DownloadState) {
            if (state !is DownloadState.Failed) return
            if (uiState.mirrorsEnabled) return
            if (autoMirrorRetriedVersion == state.version) return
            if (!isSourceFailure(state.reason)) return
            autoMirrorRetriedVersion = state.version
            viewModelScope.launch {
                settingsStore.setMirrorsEnabled(true)
                uiState =
                    uiState.copy(
                        mirrorsEnabled = true,
                        message = "直连不通，已自动开启镜像，正在重试…",
                    )
                // 等旧的前台服务收尾（stopSelf）后再拉起新请求
                delay(1500)
                startDownload(forceMirrors = true, keepMessage = true)
            }
        }

        fun downloadUpdate() = startDownload(forceMirrors = false)

        private fun startDownload(
            forceMirrors: Boolean,
            keepMessage: Boolean = false,
        ) {
            val info = uiState.updateInfo
            val url = info?.apkUrl
            if (info == null || url.isNullOrBlank()) {
                uiState = uiState.copy(message = "请先检查更新")
                return
            }
            val mirrorsAllowed =
                (uiState.mirrorsEnabled || forceMirrors) &&
                    ResumableDownload.normalizeDigest(info.apkDigest) != null
            val started =
                runCatching {
                    ApkDownloadService.start(
                        context,
                        DownloadRequest(
                            version = info.version,
                            url = url,
                            sizeBytes = info.apkSize,
                            digest = info.apkDigest,
                            // 没有官方摘要时一律禁用镜像候选，代码层不给自己留后门
                            mirrorsEnabled = mirrorsAllowed,
                            // 增量能不能用由 ApkDownloader 按本机基线决定，这里只负责把地址带过去
                            patchUrl = info.patchUrl,
                        ),
                    )
                }.isSuccess
            if (!started) {
                uiState = uiState.copy(message = "无法启动下载，请回到应用后重试", launching = false)
            } else {
                // 同一帧就把按钮切成「取消下载」：服务起来之前这段时间界面不能是死的
                uiState =
                    uiState.copy(
                        launching = true,
                        message = if (keepMessage) uiState.message else null,
                    )
                clearLaunchingIfStalled()
            }
        }

        /**
         * 服务迟迟没把状态接过去时的兜底：把「启动中」标记撤掉，按钮回到可点状态。
         *
         * 不设这条的话，若前台服务被系统拒绝（后台启动受限、权限被撤），
         * 界面会永远停在「正在准备更新」，用户既不能重试也不能取消。
         * 3 秒按实测留足余量：正常路径上服务在 200ms 内就落好了 Preparing。
         */
        private fun clearLaunchingIfStalled() {
            viewModelScope.launch {
                delay(LAUNCH_STALL_MS)
                if (uiState.launching && uiState.download is DownloadState.Idle) {
                    uiState = uiState.copy(launching = false, message = "下载服务没有响应，请重试")
                }
            }
        }

        /**
         * 打开窗口时的预热：把「点下载之后才做」的两件耗时的事搬到用户读更新说明的时候。
         *
         *  - 基线包 sha256（32 MB 约 1.2 s）：命中磁盘旁注只读几百字节；
         *  - 增量清单（GitHub 往返实测 7.3 s）：它只用来回答「能不能少下几十 MB」。
         * 两件事都不改变用户此刻看到的任何东西，纯属把等待挪到没有等待的位置。
         * 失败静默——预热不成就回到原来的时机现做。
         */
        private fun warmUp(info: UpdateChecker.UpdateInfo?) {
            val target = info ?: return
            if (target.apkUrl == null) return
            viewModelScope.launch {
                downloader.warmBaselineDigest(BuildConfig.VERSION_NAME)
                downloader.prefetchPatch(target.version, target.patchUrl)
            }
        }

        fun setMirrorsEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsStore.setMirrorsEnabled(enabled) }
        }

        /**
         * 取消下载。
         *
         * 两件事都做，顺序重要：
         * 1. 直接调 [ApkDownloader.cancel] —— 它同步把状态落成 Paused，**按钮当场变回「继续下载」**；
         * 2. 再发 cancel Intent 让前台服务收工（服务没在跑时 startService 会被系统拒绝，内部已兜住）。
         *
         * 只发 Intent 的话，按钮要等服务被调度起来、跑到 onStartCommand 才更新，
         * 那一下的空窗正是「点了取消没反应」。两条路都幂等，重复取消不会互相干扰。
         */
        fun cancelDownload() {
            uiState = uiState.copy(launching = false)
            downloader.cancel()
            ApkDownloadService.cancel(context)
        }

        fun installUpdate() {
            val ready = uiState.download as? DownloadState.Ready ?: return
            // 安装包可能在下载完成后被系统清理；先核对文件，失效时状态会切到「重新下载」
            if (!downloader.validateReady()) return
            if (!apkInstaller.canInstall()) {
                uiState = uiState.copy(message = "请先允许「安装未知应用」，返回后再点安装")
                runCatching { context.startActivity(apkInstaller.unknownSourcesIntent()) }
                    .onFailure { uiState = uiState.copy(message = "无法打开系统授权页：${it.message}") }
                return
            }
            apkInstaller
                .install(ready.file)
                .onFailure { uiState = uiState.copy(message = "无法启动安装：${it.message}") }
        }

        fun clearUpdateCache() {
            // 兜住竞态：确认框弹出之后才开始下载时，这里必须拦住，否则会删掉正在写入的分片
            if (uiState.download is DownloadState.Running) {
                uiState = uiState.copy(message = "下载进行中，请先取消下载再清理")
                return
            }
            viewModelScope.launch {
                val deleted = withContext(Dispatchers.IO) { downloader.clearCache() }
                refreshUpdateCache()
                uiState = uiState.copy(message = if (deleted > 0) "已清理下载缓存" else "没有可清理的缓存")
            }
        }
    }

/** 「点下载」到「服务接住」的容忍上限；超过就认为这次启动没成 */
private const val LAUNCH_STALL_MS = 3_000L

/** 网络/下载源类失败才值得换镜像；磁盘不足、校验失败换源也没用 */
internal fun isSourceFailure(reason: String): Boolean =
    listOf("网络", "源", "HTTP", "停滞", "过慢", "中断", "响应", "未完成").any { reason.contains(it) }
