package com.zhijian.schedule.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        repository.settings,
        repository.activeTimetable,
    ) { settings, timetable ->
        ProfileUiState(settings = settings, timetable = timetable)
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
