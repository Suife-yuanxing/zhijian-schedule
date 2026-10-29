package com.zhijian.schedule.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        repository: ScheduleRepository,
    ) : ViewModel() {
        val settings =
            repository.settings
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    }
