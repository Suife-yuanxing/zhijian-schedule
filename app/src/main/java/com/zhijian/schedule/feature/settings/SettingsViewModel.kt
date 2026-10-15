package com.zhijian.schedule.feature.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.alarm.AlarmScheduler
import com.zhijian.schedule.calendar.CalendarSyncManager
import com.zhijian.schedule.capsule.CapsuleCoordinator
import com.zhijian.schedule.capsule.CapsuleNotifier
import com.zhijian.schedule.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.BackupManager
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.update.ApkDownloadService
import com.zhijian.schedule.update.ApkDownloader
import com.zhijian.schedule.update.ApkInstaller
import com.zhijian.schedule.update.DownloadRequest
import com.zhijian.schedule.update.DownloadState
import com.zhijian.schedule.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
    val sections: List<SectionTimeEntity> = emptyList(),
    val exactAlarmOk: Boolean = true,
    val calendarAvailable: Boolean = false,
    /** 系统是否放行实时活动（流体云）；null = 系统不支持查询 */
    val capsulePromoteAllowed: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val updateInfo: UpdateChecker.UpdateInfo? = null,
    val updateChecked: Boolean = false,
    /** 检查更新的失败原因；非空时卡片显示它，不再用顶部横幅 */
    val updateError: String? = null,
    /** 下载状态（唯一事实来源在 ApkDownloader） */
    val download: DownloadState = DownloadState.Idle,
    /** `filesDir/updates/` 当前占用字节数；> 0 时卡片出现「清理下载缓存」 */
    val updateCacheBytes: Long = 0L,
    /** 系统是否放行「安装未知应用」 */
    val canInstallApk: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val backupManager: BackupManager,
    private val calendarSyncManager: CalendarSyncManager,
    private val updateChecker: UpdateChecker,
    private val alarmScheduler: AlarmScheduler,
    private val capsuleCoordinator: CapsuleCoordinator,
    private val downloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        viewModelScope.launch {
            combine(
                repository.settings,
                repository.activeTimetable,
                repository.activeSections,
            ) { settings, timetable, sections -> Triple(settings, timetable, sections) }
                .collect { (settings, timetable, sections) ->
                    uiState = uiState.copy(
                        settings = settings,
                        timetable = timetable,
                        sections = sections,
                        exactAlarmOk = alarmScheduler.canScheduleExact(),
                        calendarAvailable = calendarAvailable(),
                        capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                    )
                }
        }
        viewModelScope.launch {
            downloader.state.collect { state ->
                uiState = uiState.copy(
                    download = state,
                    canInstallApk = apkInstaller.canInstall(),
                )
                // 只在终态重算占盘，避免下载中每个分片都去列目录
                if (state !is DownloadState.Running) refreshUpdateCache()
            }
        }
    }

    private suspend fun calendarAvailable(): Boolean = withContext(Dispatchers.IO) {
        calendarSyncManager.resolveAuthority() != null
    }

    /** 从系统设置返回后刷新「流体云显示实时活动」放行状态 */
    fun refreshCapsulePromote() {
        uiState = uiState.copy(capsulePromoteAllowed = CapsuleNotifier.canPromote(context))
    }

    fun refreshAlarmPermission() {
        uiState = uiState.copy(exactAlarmOk = alarmScheduler.canScheduleExact())
    }

    /** 从「安装未知应用」授权页返回后刷新放行状态 */
    fun refreshInstallPermission() {
        uiState = uiState.copy(canInstallApk = apkInstaller.canInstall())
    }

    private suspend fun refreshUpdateCache() {
        val bytes = withContext(Dispatchers.IO) { downloader.cacheBytes() }
        uiState = uiState.copy(updateCacheBytes = bytes)
    }

    fun clearMessage() {
        uiState = uiState.copy(message = null)
    }

    // ---------- 课表信息 ----------

    fun saveTimetable(name: String, semester: String, startDate: String, totalWeeks: Int) {
        val timetable = uiState.timetable ?: return
        if (TimeUtils.parseDate(startDate) == null) {
            uiState = uiState.copy(message = "开学日期格式应为 yyyy-MM-dd")
            return
        }
        viewModelScope.launch {
            repository.updateTimetable(
                timetable.copy(
                    name = name.trim().ifBlank { timetable.name },
                    semester = semester.trim(),
                    startDate = startDate.trim(),
                    totalWeeks = totalWeeks.coerceIn(1, 30),
                )
            )
            uiState = uiState.copy(message = "课表信息已保存")
        }
    }

    fun saveSectionTime(section: SectionTimeEntity, start: String, end: String) {
        if (TimeUtils.parseTime(start) == null || TimeUtils.parseTime(end) == null) {
            uiState = uiState.copy(message = "时间格式应为 HH:mm")
            return
        }
        viewModelScope.launch {
            repository.updateSectionTime(section.copy(startTime = start.trim(), endTime = end.trim()))
            uiState = uiState.copy(message = "作息时间已更新")
        }
    }

    // ---------- 备份 ----------

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            val result = runCatching {
                val json = backupManager.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入文件")
                }
                "备份已导出"
            }
            uiState = uiState.copy(busy = false, message = result.getOrElse { "导出失败：${it.message}" })
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            val result = runCatching {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("无法读取文件")
                }
                backupManager.restoreJson(content).getOrThrow()
            }
            uiState = uiState.copy(busy = false, message = result.getOrElse { "导入失败：${it.message}" })
        }
    }

    // ---------- 日历 ----------

    fun setCalendarSync(enabled: Boolean) {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            val timetable = repository.activeTimetable.first()
            val courses = repository.activeCourses.first()
            val sections = repository.activeSections.first()
            val result = if (enabled) {
                if (timetable == null) Result.failure(IllegalStateException("没有可用课表"))
                else calendarSyncManager.syncAll(courses, timetable, sections)
            } else {
                calendarSyncManager.clearAll()
            }
            settingsStore.setCalendarSync(enabled && result.isSuccess)
            val message = result.fold(
                onSuccess = { n -> if (enabled) "已写入 $n 个课程事件" else "已清除日历中的课程" },
                onFailure = { "日历同步失败：${it.message}" },
            )
            uiState = uiState.copy(busy = false, message = message)
        }
    }

    // ---------- 实时胶囊 ----------

    fun setCapsule(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setCapsule(enabled)
            if (enabled) {
                LiveCapsuleService.start(context)
            } else {
                LiveCapsuleService.stop(context)
                capsuleCoordinator.cancelTransition()
            }
            uiState = uiState.copy(capsulePromoteAllowed = CapsuleNotifier.canPromote(context))
        }
    }

    // ---------- 检查更新 ----------

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
                // 检查结果改用卡片状态行呈现，不再复用顶部横幅
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

    // ---------- 应用内下载 ----------

    fun downloadUpdate() {
        val info = uiState.updateInfo
        val url = info?.apkUrl
        if (info == null || url.isNullOrBlank()) {
            uiState = uiState.copy(message = "请先检查更新")
            return
        }
        ApkDownloadService.start(
            context,
            DownloadRequest(
                version = info.version,
                url = url,
                sizeBytes = info.apkSize,
                digest = info.apkDigest,
            ),
        )
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
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { downloader.clearCache() }
            refreshUpdateCache()
            uiState = uiState.copy(message = if (deleted > 0) "已清理下载缓存" else "没有可清理的缓存")
        }
    }
}
