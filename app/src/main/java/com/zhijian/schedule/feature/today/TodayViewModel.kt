package com.zhijian.schedule.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.db.HolidayEntity
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

/** 组成一节课的单个小节时段（来自作息表），节与节之间是课间 */
data class TodaySection(val start: LocalTime, val end: LocalTime)

data class TodayCourseItem(
    val course: CourseEntity,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    /** 按小节拆分的时段（课节轨道用）；数据缺失时为空 */
    val sections: List<TodaySection> = emptyList(),
)

data class TodayUiState(
    val timetableName: String = "",
    val week: Int? = null,
    val today: LocalDate = LocalDate.now(),
    val courses: List<TodayCourseItem> = emptyList(),
    /** 今天是法定假日（休）或调休补班日（班）；平时为 null */
    val todayHoliday: HolidayEntity? = null,
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val holidayDao: HolidayDao,
) : ViewModel() {

    val uiState: StateFlow<TodayUiState> = combine(
        repository.activeTimetable,
        repository.activeCourses,
        repository.activeSections,
        holidayDao.observeAll(),
    ) { timetable, courses, sections, holidays ->
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
                    val segs = (c.startSection..c.endSection).mapNotNull { no ->
                        val row = sections.getOrNull(no - 1) ?: return@mapNotNull null
                        val st = TimeUtils.parseTime(row.startTime) ?: return@mapNotNull null
                        val en = TimeUtils.parseTime(row.endTime) ?: return@mapNotNull null
                        TodaySection(st, en)
                    }
                    TodayCourseItem(
                        course = c,
                        startTime = sections.getOrNull(c.startSection - 1)?.startTime
                            ?.let { TimeUtils.parseTime(it) },
                        endTime = sections.getOrNull(c.endSection - 1)?.endTime
                            ?.let { TimeUtils.parseTime(it) },
                        sections = segs,
                    )
                }
        } else emptyList()
        TodayUiState(
            timetableName = timetable?.name.orEmpty(),
            week = week,
            today = today,
            courses = items,
            todayHoliday = holidays.firstOrNull { it.date == today.toString() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUiState())

    fun deleteCourse(id: Long) {
        viewModelScope.launch { repository.deleteCourse(id) }
    }
}
