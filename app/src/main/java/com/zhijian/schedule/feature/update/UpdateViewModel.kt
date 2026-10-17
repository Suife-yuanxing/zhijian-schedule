package com.zhijian.schedule.feature.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.update.ApkDownloadService
import com.zhijian.schedule.update.ApkDownloader
import com.zhijian.schedule.update.ApkInstaller
import com.zhijian.schedule.update.DownloadRequest
import com.zhijian.schedule.update.DownloadState
import com.zhijian.schedule.update.ResumableDownload
import com.zhijian.schedule.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

/** 检查更新独立页的状态与动作；更新检查/下载的机器都挂在 Singleton 上，本 VM 只做透传与聚合 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
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
                uiState = uiState.copy(download = state)
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
            }
        } else {
            checkUpdate()
        }
    }

    private suspend fun refreshUpdateCache() {
        val bytes = withContext(Dispatchers.IO) { downloader.cacheBytes() }
        uiState = uiState.copy(updateCacheBytes = bytes)
    }

    fun checkUpdate() {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, updateChecked = false, updateError = null)
            val result = updateChecker.check()
            val info = result.getOrNull()
            uiState = uiState.copy(
                busy = false,
                updateChecked = true,
                updateInfo = info,
                updateError = result.exceptionOrNull()
                    ?.let { it.message ?: it::class.simpleName ?: "检查更新失败" },
                message = null,
            )
            // 磁盘上要是已经有下好的安装包或断点，直接把状态恢复出来，别又下一遍
            if (info?.apkUrl != null) {
                val version = info.version
                val size = info.apkSize
                withContext(Dispatchers.IO) { downloader.restoreFromDisk(version, size) }
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
            uiState = uiState.copy(
                mirrorsEnabled = true,
                message = "直连不通，已自动开启镜像，正在重试…",
            )
            // 等旧的前台服务收尾（stopSelf）后再拉起新请求
            delay(1500)
            startDownload(forceMirrors = true, keepMessage = true)
        }
    }

    /** 网络/下载源类失败才值得换镜像；磁盘不足、校验失败换源也没用 */
    private fun isSourceFailure(reason: String): Boolean =
        listOf("网络", "源", "HTTP", "停滞", "过慢", "中断", "响应", "未完成").any { reason.contains(it) }

    fun downloadUpdate() = startDownload(forceMirrors = false)

    private fun startDownload(forceMirrors: Boolean, keepMessage: Boolean = false) {
        val info = uiState.updateInfo
        val url = info?.apkUrl
        if (info == null || url.isNullOrBlank()) {
            uiState = uiState.copy(message = "请先检查更新")
            return
        }
        val mirrorsAllowed = (uiState.mirrorsEnabled || forceMirrors) &&
            ResumableDownload.normalizeDigest(info.apkDigest) != null
        val started = runCatching {
            ApkDownloadService.start(
                context,
                DownloadRequest(
                    version = info.version,
                    url = url,
                    sizeBytes = info.apkSize,
                    digest = info.apkDigest,
                    // 没有官方摘要时一律禁用镜像候选，代码层不给自己留后门
                    mirrorsEnabled = mirrorsAllowed,
                ),
            )
        }.isSuccess
        if (!started) {
            uiState = uiState.copy(message = "无法启动下载，请回到应用后重试")
        } else if (!keepMessage) {
            uiState = uiState.copy(message = null)
        }
    }

    fun setMirrorsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMirrorsEnabled(enabled) }
    }

    fun cancelDownload() {
        ApkDownloadService.cancel(context)
    }

    fun installUpdate() {
        val ready = uiState.download as? DownloadState.Ready ?: return
        if (!apkInstaller.canInstall()) {
            uiState = uiState.copy(message = "请先允许「安装未知应用」，返回后再点安装")
            runCatching { context.startActivity(apkInstaller.unknownSourcesIntent()) }
                .onFailure { uiState = uiState.copy(message = "无法打开系统授权页：${it.message}") }
            return
        }
        apkInstaller.install(ready.file)
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
