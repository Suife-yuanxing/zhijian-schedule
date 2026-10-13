package com.zhijian.schedule.alarm

import android.content.Context
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.widget.updateAllWidgets
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 监听课表数据变化，自动重排精确闹钟（新增/编辑/删除/导入/同步后均生效）。
 */
@Singleton
class ReminderCoordinator @Inject constructor(
    private val repository: ScheduleRepository,
    private val alarmScheduler: AlarmScheduler,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private data class Snapshot(
        val timetable: TimetableEntity?,
        val courses: List<CourseEntity>,
        val sections: List<SectionTimeEntity>,
        val reminderEnabled: Boolean,
        val reminderMinutes: Int,
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
                repository.settings,
            ) { timetable, courses, sections, settings ->
                Snapshot(
                    timetable = timetable,
                    courses = courses,
                    sections = sections,
                    reminderEnabled = settings.reminderEnabled,
                    reminderMinutes = settings.reminderMinutes,
                )
            }
                .distinctUntilChanged()
                .debounce(800)
                .collect { snapshot ->
                    val timetable = snapshot.timetable ?: return@collect
                    if (snapshot.sections.isEmpty()) return@collect
                    alarmScheduler.rescheduleAll(
                        courses = snapshot.courses,
                        timetable = timetable,
                        sections = snapshot.sections,
                        reminderEnabled = snapshot.reminderEnabled,
                        reminderMinutes = snapshot.reminderMinutes,
                    )
                    // 联动刷新桌面小组件
                    updateAllWidgets(context)
                }
        }
    }

    /** 设备重启 / 应用更新后，闹钟被系统清空，主动触发一次收集 */
    fun refresh() {
        start()
    }
}
