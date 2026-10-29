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
import com.zhijian.schedule.feature.exam.ExamCountdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ProfileUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
    /** 当前教学周；假期/学期外为 null */
    val week: Int? = null,
    val courseCount: Int = 0,
    val weightedGpa: Double? = null,
    val totalCredits: Double = 0.0,
    /** 最近一场考试的预览文案，如「高等数学 · 3 天」；无考试为空 */
    val examPreview: String = "",
)

@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val gradeDao: GradeDao,
    ) : ViewModel() {
        val uiState: StateFlow<ProfileUiState> =
            combine(
                repository.settings,
                repository.activeTimetable,
                repository.activeCourses,
                gradeDao.observeAll(),
                repository.activeExams,
            ) { settings, timetable, courses, grades, exams ->
                val today = LocalDate.now()
                val week =
                    timetable?.let { table ->
                        TimeUtils
                            .parseDate(table.startDate)
                            ?.let { TimeUtils.weekOfSemester(it, table.totalWeeks, today) }
                    }
                val stats = GradeParser.stats(grades.map { it.toRecord() })
                val nowDateTime = java.time.LocalDateTime.now()
                val nextExam =
                    exams
                        .filter { it.examTime >= System.currentTimeMillis() }
                        .minByOrNull { it.examTime }
                val examPreview =
                    nextExam
                        ?.let { "${it.name} · ${ExamCountdown.badge(it.examTime, nowDateTime)}" }
                        .orEmpty()
                ProfileUiState(
                    settings = settings,
                    timetable = timetable,
                    week = week,
                    courseCount = courses.size,
                    weightedGpa = stats.weightedGpa,
                    totalCredits = stats.totalCredits,
                    examPreview = examPreview,
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

        fun setReminder(
            enabled: Boolean,
            minutes: Int,
        ) {
            viewModelScope.launch { settingsStore.setReminder(enabled, minutes) }
        }
    }
