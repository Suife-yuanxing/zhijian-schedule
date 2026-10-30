package com.zhijian.schedule.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.db.HolidayEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimeModeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ScheduleUiState(
    val timetable: TimetableEntity? = null,
    val courses: List<CourseEntity> = emptyList(),
    val sections: List<SectionTimeEntity> = emptyList(),
    val mode: TimeModeEntity? = null,
    val settings: AppSettings = AppSettings(),
    val selectedWeek: Int = 1,
    val currentWeek: Int? = null,
    val today: LocalDate = LocalDate.now(),
    /** 节假日/调休标注：yyyy-MM-dd → 实体 */
    val holidays: Map<String, HolidayEntity> = emptyMap(),
) {
    val totalWeeks: Int get() = timetable?.totalWeeks ?: 20
    val startDate: LocalDate get() = TimeUtils.parseDate(timetable?.startDate) ?: today
    val sectionCount: Int get() = sections.size.coerceAtLeast(1)
    val periodCount: Int get() = sections.maxOfOrNull { it.periodNo } ?: 0

    /** 显示第 week 周、周几 的课程 */
    fun coursesAt(
        week: Int,
        day: Int,
    ): List<CourseEntity> =
        courses
            .filter { c ->
                c.dayOfWeek == day &&
                    com.zhijian.schedule.core.util.WeekMask
                        .contains(c.weekMask, week)
            }.sortedBy { it.startSection }

    fun dateOf(
        week: Int,
        day: Int,
    ): LocalDate = TimeUtils.dateOfWeekDay(startDate, week, day)

    /** 展示的第 week 周是否含调休补班日（补班日强制显示对应周末列） */
    fun weekHasMakeupWorkday(week: Int): Boolean =
        (6..7).any { dow ->
            holidays[dateOf(week, dow).toString()]?.isOffDay == false
        }
}

@HiltViewModel
class ScheduleViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val holidayDao: HolidayDao,
    ) : ViewModel() {
        private val selectedWeek = MutableStateFlow(0)

        private data class Base(
            val timetable: TimetableEntity?,
            val courses: List<CourseEntity>,
            val sections: List<SectionTimeEntity>,
            val settings: AppSettings,
        )

        private val base =
            combine(
                repository.activeTimetable,
                repository.activeCourses,
                repository.activeSections,
                repository.settings,
            ) { t, c, s, st -> Base(t, c, s, st) }

        val uiState: StateFlow<ScheduleUiState> =
            combine(base, selectedWeek, holidayDao.observeAll()) { b, sel, holidays ->
                val today = LocalDate.now()
                val start = TimeUtils.parseDate(b.timetable?.startDate)
                val currentWeek =
                    if (start != null && b.timetable != null) {
                        TimeUtils.weekOfSemester(start, b.timetable.totalWeeks, today)
                    } else {
                        null
                    }
                val week =
                    when {
                        sel != 0 -> sel
                        currentWeek != null -> currentWeek
                        else -> 1
                    }
                ScheduleUiState(
                    timetable = b.timetable,
                    courses = b.courses,
                    sections = b.sections,
                    settings = b.settings,
                    selectedWeek = week,
                    currentWeek = currentWeek,
                    today = today,
                    holidays = holidays.associateBy { it.date },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

        fun selectWeek(week: Int) {
            selectedWeek.value = week.coerceIn(1, uiState.value.totalWeeks)
        }

        /**
         * 上一次「按哪份课表定位过页码」的锚点签名（见 [WeekAnchorPolicy]）。
         *
         * 存在 ViewModel 而不是组合里：切 tab / 从二级页返回 / Activity 重建都会让
         * 课表页重新进入组合，但 ViewModel 归 back stack entry 所有、且 popUpTo(saveState)
         * 会一并保存它。放在 remember 里的话每次重建都是 null，等于「每次进入都重新定位」，
         * 正是「切页刷新位置错误」本身。
         */
        private var lastAnchor: String? = null

        /**
         * 这次进入要不要把 pager 拉回自然当前周。
         *
         * 只有课表本身换了（id / 开学日期 / 总周数变化）才返回 true；
         * 用户手选的周次在同一份课表里始终保留。
         */
        fun shouldRelocate(anchor: String?): Boolean {
            if (!WeekAnchorPolicy.shouldRelocate(lastAnchor, anchor)) return false
            lastAnchor = anchor
            return true
        }

        fun deleteCourse(id: Long) {
            viewModelScope.launch { repository.deleteCourse(id) }
        }
    }
