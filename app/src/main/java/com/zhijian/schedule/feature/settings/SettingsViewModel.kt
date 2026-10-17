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
import com.zhijian.schedule.capsule.CapsuleReliability
import com.zhijian.schedule.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.BackupManager
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
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
    val calendarPermissionGranted: Boolean = false,
    /** 本次会话里被拒绝过；用于把「去授权」换成「去设置」 */
    val calendarPermissionDenied: Boolean = false,
    val calendarPermissionPermanentlyDenied: Boolean = false,
    /** 系统是否放行实时活动（流体云）；null = 系统不支持查询 */
    val capsulePromoteAllowed: Boolean? = null,
    /** 是否已允许后台运行（忽略电池优化）；未允许时临近切换会用系统闹钟兜底 */
    val batteryOptimizationIgnored: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val backupManager: BackupManager,
    private val calendarSyncManager: CalendarSyncManager,
    private val alarmScheduler: AlarmScheduler,
    private val capsuleCoordinator: CapsuleCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var uiState by mutableStateOf(SettingsUiState())
        private set

    /** 用户从权限设置页返回后，若已授权就补上这次开启 */
    private var pendingCalendarEnable = false

    init {
        uiState = uiState.copy(
            calendarPermissionGranted = calendarSyncManager.hasPermission(),
            calendarAvailable = calendarSyncManager.hasCalendarProvider(),
        )
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
                        calendarPermissionGranted = calendarSyncManager.hasPermission(),
                        capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                        batteryOptimizationIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context),
                    )
                }
        }
    }

    private suspend fun calendarAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 未授权时查询 Provider 必然 SecurityException，不能据此判定「没有日历」，
        // 否则开关被禁用，权限弹窗永远弹不出来
        if (calendarSyncManager.hasPermission()) {
            calendarSyncManager.resolveAuthority() != null
        } else {
            calendarSyncManager.hasCalendarProvider()
        }
    }

    /** 从系统设置返回后刷新权限、流体云放行状态，并补上待开启的日历同步 */
    fun refreshRuntimeState() {
        viewModelScope.launch {
            val granted = calendarSyncManager.hasPermission()
            uiState = uiState.copy(
                capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                batteryOptimizationIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context),
                exactAlarmOk = alarmScheduler.canScheduleExact(),
                calendarPermissionGranted = granted,
                calendarAvailable = calendarAvailable(),
                calendarPermissionPermanentlyDenied =
                    if (granted) false else uiState.calendarPermissionPermanentlyDenied,
            )
            if (!granted) {
                // 同步开关还开着但权限被收回：重新授权后自动补刷
                if (uiState.settings.calendarSyncEnabled) pendingCalendarEnable = true
                return@launch
            }
            when {
                pendingCalendarEnable -> {
                    pendingCalendarEnable = false
                    setCalendarSyncInternal(true)
                }
                uiState.settings.calendarSyncEnabled -> syncCalendarIfNeeded()
            }
        }
    }

    /** 权限被收回/恢复后开关状态可能和系统不一致，回前台时按指纹补刷一次 */
    private suspend fun syncCalendarIfNeeded() {
        val timetable = repository.activeTimetable.first() ?: return
        val courses = repository.activeCourses.first()
        val sections = repository.activeSections.first()
        calendarSyncManager.syncIfNeeded(courses, timetable, sections)
    }

    fun refreshAlarmPermission() {
        uiState = uiState.copy(exactAlarmOk = alarmScheduler.canScheduleExact())
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

    fun onCalendarPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (granted) {
            pendingCalendarEnable = false
            uiState = uiState.copy(
                calendarPermissionGranted = true,
                calendarPermissionDenied = false,
                calendarPermissionPermanentlyDenied = false,
            )
            viewModelScope.launch { setCalendarSyncInternal(true) }
        } else {
            uiState = uiState.copy(
                calendarPermissionGranted = false,
                calendarPermissionDenied = true,
                calendarPermissionPermanentlyDenied = permanentlyDenied,
                message = if (permanentlyDenied) {
                    "日历权限已被拒绝，请到系统设置中开启"
                } else {
                    "未授予日历权限，无法写入系统日历"
                },
            )
        }
    }

    /** 用户点了「去设置」：授权成功后回到前台自动开启同步 */
    fun onCalendarPermissionSettingsOpened() {
        pendingCalendarEnable = true
    }

    fun setCalendarSync(enabled: Boolean) {
        viewModelScope.launch { setCalendarSyncInternal(enabled) }
    }

    private suspend fun setCalendarSyncInternal(enabled: Boolean) {
        if (enabled && !calendarSyncManager.hasPermission()) {
            uiState = uiState.copy(message = "请先授予日历读写权限")
            return
        }
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
        uiState = uiState.copy(
            busy = false,
            message = message,
            calendarPermissionGranted = calendarSyncManager.hasPermission(),
            calendarAvailable = calendarAvailable(),
        )
    }

    // ---------- 实时胶囊 ----------

    fun setCapsule(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setCapsule(enabled)
            if (enabled) {
                LiveCapsuleService.start(context)
                // 开启胶囊时顺手申请后台运行：否则 ColorOS 会冻结进程，状态切换只能靠闹钟兜底
                if (!CapsuleReliability.isBatteryOptimizationIgnored(context)) {
                    CapsuleReliability.requestIgnoreBatteryOptimization(context)
                }
            } else {
                LiveCapsuleService.stop(context)
                capsuleCoordinator.cancelTransition()
            }
            uiState = uiState.copy(
                capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                batteryOptimizationIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context),
                exactAlarmOk = alarmScheduler.canScheduleExact(),
            )
        }
    }

    fun setCapsuleAlarmFallback(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setCapsuleAlarmFallback(enabled) }
    }

}
