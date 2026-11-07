package com.zhijian.schedule.feature.courseeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.core.util.WeekSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class EditorUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val courseId: Long = -1,
    val totalWeeks: Int = 20,
    val name: String = "",
    val room: String = "",
    val teacher: String = "",
    val note: String = "",
    val dayOfWeek: Int = 0,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val weeks: Set<Int> = emptySet(),
    val color: Int = 0,
    val saved: Boolean = false,
    /**
     * 星期几 → 这一天上「别的课」的周次。
     *
     * 周次选择唯一的判断依据不是「我选了几周」，而是「这几周有没有撞课」。
     * 旧界面把 20 个格子做成同质方块，这条信息在结构上就不存在；
     * 现在由仓库里的其他课程预先算好，交给周格画一个小圆点。
     */
    val occupiedByDay: Map<Int, Set<Int>> = emptyMap(),
    /** 本学期当前周次；不在学期内或开学日期缺失时为 null（横条因此不滚、也不标"本周"） */
    val currentWeek: Int? = null,
) {
    val canSave: Boolean
        get() =
            name.isNotBlank() &&
                dayOfWeek in 1..7 &&
                weeks.isNotEmpty() &&
                startSection <= endSection
}

@HiltViewModel
class CourseEditorViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val courseId: Long = savedStateHandle.get<Long>("courseId") ?: -1L
        private val prefillDay: Int = savedStateHandle.get<Int>("day") ?: -1
        private val prefillSection: Int = savedStateHandle.get<Int>("section") ?: -1

        var uiState by mutableStateOf(EditorUiState())
            private set

        init {
            observeOccupiedWeeks()
            viewModelScope.launch {
                val timetable = repository.activeTimetable.first()
                val totalWeeks = timetable?.totalWeeks ?: 20
                val currentWeek = currentWeekOf(timetable?.startDate, totalWeeks)
                if (courseId > 0) {
                    val existing = repository.getCourse(courseId)
                    if (existing != null) {
                        uiState =
                            EditorUiState(
                                loading = false,
                                isEdit = true,
                                courseId = courseId,
                                totalWeeks = totalWeeks,
                                name = existing.name,
                                room = existing.room,
                                teacher = existing.teacher,
                                note = existing.note,
                                dayOfWeek = existing.dayOfWeek,
                                startSection = existing.startSection,
                                endSection = existing.endSection,
                                weeks = WeekMask.weeks(existing.weekMask).toSet(),
                                color = existing.color,
                                currentWeek = currentWeek,
                            )
                        return@launch
                    }
                }
                val defaultDay = if (prefillDay in 1..7) prefillDay else 0
                val start = if (prefillSection in 1..12) prefillSection else 1
                val end = if (prefillSection in 1..12) prefillSection else 2
                uiState =
                    EditorUiState(
                        loading = false,
                        totalWeeks = totalWeeks,
                        dayOfWeek = defaultDay,
                        startSection = start,
                        endSection = end,
                        // 新建课程不预选星期与周次：让用户主动选择，保存前两者都必须有值
                        weeks = emptySet(),
                        color =
                            ThemePresets.paper.coursePalette
                                .first()
                                .toArgb(),
                        currentWeek = currentWeek,
                    )
            }
        }

        /**
         * 本学期当前是第几周。
         *
         * 开学日期来自课表元数据，缺失或格式异常时返回 null——此时不猜"第 1 周"，
         * 因为横条一旦标错本周，用户会照着错的锚点选周次。
         */
        private fun currentWeekOf(
            startDate: String?,
            totalWeeks: Int,
        ): Int? {
            val start = runCatching { LocalDate.parse(startDate.orEmpty()) }.getOrNull() ?: return null
            return TimeUtils.weekOfSemester(start, totalWeeks, LocalDate.now())
        }

        /**
         * 收集同学期其他课程的周次占用，按星期分组。
         *
         * 为什么放在 VM 而不是 Composable 里算：它是仓库数据的派生量，
         * 跟"用户点了几格"无关；放进 Composable 会让每次选题重算整张表。
         * 排序在编辑态与新建态都必须排除当前这门课自己——否则一打开编辑页，
         * 这门课原有的周次会全部亮成"已被占用"。
         */
        private fun observeOccupiedWeeks() {
            viewModelScope.launch {
                repository.activeCourses.collect { courses ->
                    val map = mutableMapOf<Int, MutableSet<Int>>()
                    courses.forEach { course ->
                        if (courseId > 0 && course.id == courseId) return@forEach
                        val days = map.getOrPut(course.dayOfWeek) { mutableSetOf() }
                        days += WeekMask.weeks(course.weekMask)
                    }
                    uiState = uiState.copy(occupiedByDay = map)
                }
            }
        }

        fun setField(
            name: String? = null,
            room: String? = null,
            teacher: String? = null,
            note: String? = null,
            dayOfWeek: Int? = null,
            startSection: Int? = null,
            endSection: Int? = null,
            color: Int? = null,
        ) {
            uiState =
                uiState.copy(
                    name = name ?: uiState.name,
                    room = room ?: uiState.room,
                    teacher = teacher ?: uiState.teacher,
                    note = note ?: uiState.note,
                    dayOfWeek = dayOfWeek ?: uiState.dayOfWeek,
                    startSection = startSection ?: uiState.startSection,
                    endSection = endSection ?: uiState.endSection,
                    color = color ?: uiState.color,
                )
        }

        fun toggleWeek(week: Int) {
            if (week !in 1..uiState.totalWeeks) return
            val weeks = uiState.weeks.toMutableSet()
            if (!weeks.add(week)) weeks.remove(week)
            uiState = uiState.copy(weeks = weeks)
        }

        fun setAllWeeks() {
            uiState = uiState.copy(weeks = (1..uiState.totalWeeks).toSet())
        }

        /**
         * 单/双周：在**当前区间**内取。
         *
         * 旧实现是在整学期内取，于是"先滑到 5-12 再点单周"会把区间悄悄扩成 1/3/5/…/19，
         * 用户刚设的边界没了。区间为空时才回落整学期——那是从零开始的第一个动作。
         */
        fun setParity(odd: Boolean) {
            val s = uiState
            val bounds = WeekSelection.bounds(s.weeks) ?: (1..s.totalWeeks)
            uiState = uiState.copy(weeks = WeekSelection.parity(bounds, odd, s.totalWeeks))
        }

        /** 区间滑块：端点可交叉（拖动过程中必然发生），由 [WeekSelection.range] 归一 */
        fun setWeekRange(
            start: Int,
            end: Int,
        ) {
            val s = uiState
            uiState = uiState.copy(weeks = WeekSelection.range(start, end, s.totalWeeks))
        }

        fun clearWeeks() {
            uiState = uiState.copy(weeks = emptySet())
        }

        fun save() {
            val s = uiState
            if (!s.canSave) return
            viewModelScope.launch {
                val draft =
                    CourseDraft(
                        name = s.name.trim(),
                        teacher = s.teacher.trim(),
                        room = s.room.trim(),
                        dayOfWeek = s.dayOfWeek,
                        startSection = s.startSection,
                        endSection = s.endSection,
                        weekMask = WeekMask.fromWeeks(s.totalWeeks, s.weeks),
                        color = s.color,
                        note = s.note.trim(),
                    )
                repository.saveCourse(draft, if (s.isEdit) s.courseId else null, manuallyEdited = true)
                uiState = uiState.copy(saved = true)
            }
        }

        fun delete() {
            val id = uiState.courseId
            if (id > 0) {
                viewModelScope.launch {
                    repository.deleteCourse(id)
                    uiState = uiState.copy(saved = true)
                }
            }
        }
    }
