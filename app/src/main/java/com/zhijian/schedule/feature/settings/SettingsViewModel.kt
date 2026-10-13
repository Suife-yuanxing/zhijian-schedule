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
import com.zhijian.schedule.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.BackupManager
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
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
    val busy: Boolean = false,
    val message: String? = null,
    val updateInfo: UpdateChecker.UpdateInfo? = null,
    val updateChecked: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val backupManager: BackupManager,
    private val calendarSyncManager: CalendarSyncManager,
    private val updateChecker: UpdateChecker,
    private val alarmScheduler: AlarmScheduler,
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
                    )
                }
        }
    }

    private suspend fun calendarAvailable(): Boolean = withContext(Dispatchers.IO) {
        calendarSyncManager.resolveAuthority() != null
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
            if (enabled) LiveCapsuleService.start(context) else LiveCapsuleService.stop(context)
        }
    }

    // ---------- 检查更新 ----------

    fun checkUpdate() {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, updateChecked = false)
            val result = updateChecker.check()
            uiState = uiState.copy(
                busy = false,
                updateChecked = true,
                updateInfo = result.getOrNull(),
                message = result.fold(
                    onSuccess = { info -> if (info == null) "已是最新版本" else "发现新版本 v${info.version}" },
                    onFailure = { "检查更新失败：${it.message}" },
                ),
            )
        }
    }
}
