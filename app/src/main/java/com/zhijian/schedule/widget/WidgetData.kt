package com.zhijian.schedule.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.theme.ThemeIds
import com.zhijian.schedule.core.theme.ThemePreset
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): ScheduleRepository
    fun settingsStore(): SettingsStore
}

data class WidgetCourse(
    val name: String,
    val room: String,
    val startTime: String,
    val endTime: String,
    val color: Int,
    val isCurrent: Boolean = false,
    val isNext: Boolean = false,
)

data class WidgetTodayData(
    val dayName: String,
    val week: Int?,
    val courses: List<WidgetCourse>,
    val themeId: String = ThemeIds.PAPER,
    val showWeekend: Boolean = false,
)

data class WidgetNextData(
    val course: WidgetCourse?,
    val countdownText: String,
    val themeId: String = ThemeIds.PAPER,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetRepositoryEntryPoint {
    fun repository(): ScheduleRepository
}

suspend fun loadTodayData(context: Context): WidgetTodayData {
    val entry = EntryPointAccessors.fromApplication(
        context.applicationContext, WidgetEntryPoint::class.java
    )
    val repository = entry.repository()
    val settings = entry.settingsStore().settings.first()
    val timetable = repository.activeTimetable.first()
    val courses = repository.activeCourses.first()
    val sections = repository.activeSections.first()
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val week = timetable?.let { t ->
        TimeUtils.parseDate(t.startDate)?.let { start ->
            TimeUtils.weekOfSemester(start, t.totalWeeks, today)
        }
    }
    val dow = TimeUtils.dayOfWeek(today)
    val dayCourses = if (week != null) {
        courses.filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
            .sortedBy { it.startSection }
    } else emptyList()

    val nowTime = now.toLocalTime()
    val items = dayCourses.map { c ->
        val st = sections.getOrNull(c.startSection - 1)?.startTime.orEmpty()
        val et = sections.getOrNull(c.endSection - 1)?.endTime.orEmpty()
        val stTime = TimeUtils.parseTime(st)
        val etTime = TimeUtils.parseTime(et)
        WidgetCourse(
            name = c.name,
            room = c.room,
            startTime = st,
            endTime = et,
            color = c.color,
            isCurrent = stTime != null && etTime != null &&
                !nowTime.isBefore(stTime) && !nowTime.isAfter(etTime),
        )
    }
    return WidgetTodayData(
        dayName = TimeUtils.dayName(dow),
        week = week,
        courses = items,
        themeId = settings.themeId,
        showWeekend = settings.showWeekend,
    )
}

suspend fun loadNextData(context: Context): WidgetNextData {
    val today = loadTodayData(context)
    val nowTime: LocalTime = LocalTime.now()
    val next = today.courses.firstOrNull { item ->
        TimeUtils.parseTime(item.startTime)?.let { it.isAfter(nowTime) } == true
    }
    val countdown = next?.let { item ->
        val st = TimeUtils.parseTime(item.startTime)
        if (st != null) TimeUtils.minutesText(TimeUtils.minutesUntil(LocalTime.now(), st)) else ""
    }.orEmpty()
    return WidgetNextData(
        course = next ?: today.courses.firstOrNull { it.isCurrent },
        countdownText = countdown,
        themeId = today.themeId,
    )
}

fun presetOf(themeId: String): ThemePreset = ThemePresets.byId(themeId)

fun isSystemDark(context: Context): Boolean =
    (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/** 数据变化 / 应用打开时刷新全部小组件 */
suspend fun updateAllWidgets(context: Context) {
    runCatching { TodayWidget().updateAll(context) }
    runCatching { NextCourseWidget().updateAll(context) }
}
