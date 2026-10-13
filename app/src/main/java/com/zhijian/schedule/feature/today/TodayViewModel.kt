package com.zhijian.schedule.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayCourseItem(
    val course: CourseEntity,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)

data class TodayUiState(
    val timetableName: String = "",
    val week: Int? = null,
    val today: LocalDate = LocalDate.now(),
    val courses: List<TodayCourseItem> = emptyList(),
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: ScheduleRepository,
) : ViewModel() {

    val uiState: StateFlow<TodayUiState> = combine(
        repository.activeTimetable,
        repository.activeCourses,
        repository.activeSections,
    ) { timetable, courses, sections ->
        val today = LocalDate.now()
        val start = timetable?.let { TimeUtils.parseDate(it.startDate) }
        val week = if (timetable != null && start != null) {
            TimeUtils.weekOfSemester(start, timetable.totalWeeks, today)
        } else null
        val dow = TimeUtils.dayOfWeek(today)
        val items = if (week != null) {
            courses
                .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
                .sortedBy { it.startSection }
                .map { c ->
                    TodayCourseItem(
                        course = c,
                        startTime = sections.getOrNull(c.startSection - 1)?.startTime
                            ?.let { TimeUtils.parseTime(it) },
                        endTime = sections.getOrNull(c.endSection - 1)?.endTime
                            ?.let { TimeUtils.parseTime(it) },
                    )
                }
        } else emptyList()
        TodayUiState(
            timetableName = timetable?.name.orEmpty(),
            week = week,
            today = today,
            courses = items,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUiState())

    fun deleteCourse(id: Long) {
        viewModelScope.launch { repository.deleteCourse(id) }
    }
}
