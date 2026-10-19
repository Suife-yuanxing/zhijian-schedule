package com.zhijian.schedule.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.GradeDao
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.GradeParser
import com.zhijian.schedule.core.jw.toRecord
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
    /** 当前教学周；假期/学期外为 null */
    val week: Int? = null,
    val courseCount: Int = 0,
    val weightedGpa: Double? = null,
    val totalCredits: Double = 0.0,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val gradeDao: GradeDao,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        repository.settings,
        repository.activeTimetable,
        repository.activeCourses,
        gradeDao.observeAll(),
    ) { settings, timetable, courses, grades ->
        val today = LocalDate.now()
        val week = timetable?.let { table ->
            TimeUtils.parseDate(table.startDate)
                ?.let { TimeUtils.weekOfSemester(it, table.totalWeeks, today) }
        }
        val stats = GradeParser.stats(grades.map { it.toRecord() })
        ProfileUiState(
            settings = settings,
            timetable = timetable,
            week = week,
            courseCount = courses.size,
            weightedGpa = stats.weightedGpa,
            totalCredits = stats.totalCredits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState())

    fun setTheme(id: String) {
        viewModelScope.launch { settingsStore.setTheme(id) }
    }

    fun setGridStyle(id: String) {
        viewModelScope.launch { settingsStore.setGridStyle(id) }
    }

    fun setDarkMode(mode: Int) {
        viewModelScope.launch { settingsStore.setDarkMode(mode) }
    }

    fun setShowWeekend(show: Boolean) {
        viewModelScope.launch { settingsStore.setShowWeekend(show) }
    }

    fun setReminder(enabled: Boolean, minutes: Int) {
        viewModelScope.launch { settingsStore.setReminder(enabled, minutes) }
    }
}
