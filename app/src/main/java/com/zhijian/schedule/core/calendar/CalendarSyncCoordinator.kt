package com.zhijian.schedule.core.calendar

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 监听课表数据变化，开关打开时自动重刷「纸间课表」日历
 * （导入/编辑课程、调整作息或学期信息后均生效）。
 */
@Singleton
class CalendarSyncCoordinator
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val calendarSyncManager: CalendarSyncManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var started = false

        private data class Snapshot(
            val timetable: TimetableEntity?,
            val courses: List<CourseEntity>,
            val sections: List<SectionTimeEntity>,
        )

        @OptIn(FlowPreview::class)
        fun start() {
            if (started) return
            started = true
            scope.launch {
                combine(
                    repository.activeTimetable,
                    repository.activeCourses,
                    repository.activeSections,
                ) { timetable, courses, sections -> Snapshot(timetable, courses, sections) }
                    .distinctUntilChanged()
                    .debounce(800)
                    .collect { snapshot ->
                        val timetable = snapshot.timetable ?: return@collect
                        if (snapshot.sections.isEmpty()) return@collect
                        if (!settingsStore.settings.first().calendarSyncEnabled) return@collect
                        if (!calendarSyncManager.hasPermission()) return@collect
                        calendarSyncManager.syncIfNeeded(snapshot.courses, timetable, snapshot.sections)
                    }
            }
        }
    }
