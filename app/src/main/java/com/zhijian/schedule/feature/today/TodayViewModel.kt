package com.zhijian.schedule.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.db.HolidayEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.core.util.WeekPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

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
    /** 今天是法定假日（休）或调休补班日（班）；平时为 null */
    val todayHoliday: HolidayEntity? = null,
    /** 最近一场 14 天内的考试；无则为 null */
    val nextExam: ExamEntity? = null,
)

@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val holidayDao: HolidayDao,
    ) : ViewModel() {
        /**
         * 「今天」由日期流驱动：跨零点后本周课程与「第 N 周」自己跟上（见 [WeekPlan.todayTicks]）。
         *
         * 先把手头这五路数据源合成一份 [Sources]，再与日期流两两 combine——
         * `combine` 只有 2..5 个 flow 的重载，六路得先分组，否则类型推断会整片塌掉。
         */
        private val todayTicks = WeekPlan.todayTicks()

        private val sources =
            combine(
                repository.activeTimetable,
                repository.activeCourses,
                repository.activeSections,
                holidayDao.observeAll(),
                repository.activeExams,
            ) { timetable, courses, sections, holidays, exams ->
                Sources(timetable, courses, sections, holidays, exams)
            }

        val uiState: StateFlow<TodayUiState> =
            combine(sources, todayTicks) { src, today ->
                val timetable = src.timetable
                val courses = src.courses
                val sections = src.sections
                val holidays = src.holidays
                val exams = src.exams
                val start = timetable?.let { TimeUtils.parseDate(it.startDate) }
                val week =
                    if (timetable != null && start != null) {
                        TimeUtils.weekOfSemester(start, timetable.totalWeeks, today)
                    } else {
                        null
                    }
                val dow = TimeUtils.dayOfWeek(today)
                val items =
                    if (week != null) {
                        courses
                            .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
                            .sortedBy { it.startSection }
                            .map { c ->
                                TodayCourseItem(
                                    course = c,
                                    startTime =
                                        sections
                                            .getOrNull(c.startSection - 1)
                                            ?.startTime
                                            ?.let { TimeUtils.parseTime(it) },
                                    endTime =
                                        sections
                                            .getOrNull(c.endSection - 1)
                                            ?.endTime
                                            ?.let { TimeUtils.parseTime(it) },
                                )
                            }
                    } else {
                        emptyList()
                    }
                val nowMillis = System.currentTimeMillis()
                val horizonMillis =
                    today
                        .plusDays(14)
                        .atTime(23, 59)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val nextExam =
                    exams
                        .filter { it.examTime >= nowMillis && it.examTime <= horizonMillis }
                        .minByOrNull { it.examTime }
                TodayUiState(
                    timetableName = timetable?.name.orEmpty(),
                    week = week,
                    today = today,
                    courses = items,
                    todayHoliday = holidays.firstOrNull { it.date == today.toString() },
                    nextExam = nextExam,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUiState())

        /** 今日页要观察的五路数据源，先合一份再和日期流配对 */
        private data class Sources(
            val timetable: TimetableEntity?,
            val courses: List<CourseEntity>,
            val sections: List<SectionTimeEntity>,
            val holidays: List<HolidayEntity>,
            val exams: List<ExamEntity>,
        )

        fun deleteCourse(id: Long) {
            viewModelScope.launch { repository.deleteCourse(id) }
        }
    }
