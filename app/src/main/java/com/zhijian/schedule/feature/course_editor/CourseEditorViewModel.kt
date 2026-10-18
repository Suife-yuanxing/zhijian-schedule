package com.zhijian.schedule.feature.course_editor

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
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
) {
    val canSave: Boolean
        get() = name.isNotBlank() &&
            dayOfWeek in 1..7 &&
            weeks.isNotEmpty() &&
            startSection <= endSection
}

@HiltViewModel
class CourseEditorViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val courseId: Long = savedStateHandle.get<Long>("courseId") ?: -1L
    private val prefillDay: Int = savedStateHandle.get<Int>("day") ?: -1
    private val prefillSection: Int = savedStateHandle.get<Int>("section") ?: -1

    var uiState by mutableStateOf(EditorUiState())
        private set

    init {
        viewModelScope.launch {
            val timetable = repository.activeTimetable.first()
            val totalWeeks = timetable?.totalWeeks ?: 20
            if (courseId > 0) {
                val existing = repository.getCourse(courseId)
                if (existing != null) {
                    uiState = EditorUiState(
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
                    )
                    return@launch
                }
            }
            val defaultDay = if (prefillDay in 1..7) prefillDay else 0
            val start = if (prefillSection in 1..12) prefillSection else 1
            val end = if (prefillSection in 1..12) prefillSection else 2
            uiState = EditorUiState(
                loading = false,
                totalWeeks = totalWeeks,
                dayOfWeek = defaultDay,
                startSection = start,
                endSection = end,
                // 新建课程不预选星期与周次：让用户主动选择，保存前两者都必须有值
                weeks = emptySet(),
                color = ThemePresets.paper.coursePalette.first().toArgb(),
            )
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
        uiState = uiState.copy(
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
        val weeks = uiState.weeks.toMutableSet()
        if (!weeks.add(week)) weeks.remove(week)
        uiState = uiState.copy(weeks = weeks)
    }

    fun setAllWeeks() {
        uiState = uiState.copy(weeks = (1..uiState.totalWeeks).toSet())
    }

    fun setParity(odd: Boolean) {
        uiState = uiState.copy(
            weeks = (1..uiState.totalWeeks).filter { if (odd) it % 2 == 1 else it % 2 == 0 }.toSet()
        )
    }

    fun clearWeeks() {
        uiState = uiState.copy(weeks = emptySet())
    }

    fun save() {
        val s = uiState
        if (!s.canSave) return
        viewModelScope.launch {
            val draft = CourseDraft(
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
