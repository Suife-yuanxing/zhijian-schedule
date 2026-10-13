package com.zhijian.schedule.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课程写入系统日历（方案 6.5）：
 * ColorOS 新版日历私有库 com.coloros.calendar，字段与 CalendarContract 一致，
 * 旧版回退原生 com.android.calendar。
 */
@Singleton
class CalendarSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val AUTHORITY_COLOROS = "com.coloros.calendar"
        private const val AUTHORITY_AOSP = "com.android.calendar"
        private const val ACCOUNT_NAME = "纸间课表"
        private const val CALENDAR_NAME = "纸间课表"
    }

    /** 探测可用的日历 Provider（ColorOS 私有库优先） */
    fun resolveAuthority(): String? {
        for (authority in listOf(AUTHORITY_COLOROS, AUTHORITY_AOSP)) {
            val ok = runCatching {
                context.contentResolver.query(
                    calendarsUri(authority),
                    arrayOf(CalendarContract.Calendars._ID),
                    null, null, null,
                )?.use { true } ?: false
            }.getOrDefault(false)
            if (ok) return authority
        }
        return null
    }

    suspend fun syncAll(
        courses: List<CourseEntity>,
        timetable: TimetableEntity,
        sections: List<SectionTimeEntity>,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val authority = resolveAuthority()
                ?: throw IllegalStateException("未找到系统日历")
            val calendarId = ensureCalendar(authority)
                ?: throw IllegalStateException("无法创建日历（请检查日历权限）")
            deleteEvents(authority, calendarId)

            val startDate = TimeUtils.parseDate(timetable.startDate)
                ?: throw IllegalStateException("开学日期无效")
            var count = 0
            courses.forEach { course ->
                val startText = sections.getOrNull(course.startSection - 1)?.startTime
                val endText = sections.getOrNull(course.endSection - 1)?.endTime
                val startTime = TimeUtils.parseTime(startText) ?: return@forEach
                val endTime = TimeUtils.parseTime(endText) ?: startTime.plusMinutes(45)
                WeekMask.weeks(course.weekMask).forEach { week ->
                    val date = TimeUtils.dateOfWeekDay(startDate, week, course.dayOfWeek)
                    val begin = LocalDateTime.of(date, startTime)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val finish = LocalDateTime.of(date, endTime)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    insertEvent(authority, calendarId, course, timetable, begin, finish)
                    count++
                }
            }
            count
        }
    }

    suspend fun clearAll(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val authority = resolveAuthority()
                ?: throw IllegalStateException("未找到系统日历")
            val calendarId = findCalendar(authority)
            if (calendarId == null) {
                0
            } else {
                deleteEvents(authority, calendarId)
            }
        }
    }

    private fun calendarsUri(authority: String) =
        android.net.Uri.parse("content://$authority/calendars")

    private fun eventsUri(authority: String) =
        android.net.Uri.parse("content://$authority/events")

    private fun findCalendar(authority: String): Long? {
        context.contentResolver.query(
            calendarsUri(authority),
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun ensureCalendar(authority: String): Long? {
        findCalendar(authority)?.let { return it }
        val uri = calendarsUri(authority).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFD97757.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val inserted = runCatching {
            context.contentResolver.insert(uri, values)
        }.getOrNull() ?: return null
        return runCatching { ContentUris.parseId(inserted) }.getOrNull()
    }

    private fun deleteEvents(authority: String, calendarId: Long): Int =
        runCatching {
            context.contentResolver.delete(
                eventsUri(authority),
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString()),
            )
        }.getOrDefault(0)

    private fun insertEvent(
        authority: String,
        calendarId: Long,
        course: CourseEntity,
        timetable: TimetableEntity,
        begin: Long,
        end: Long,
    ) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, course.name)
            put(CalendarContract.Events.EVENT_LOCATION, course.room)
            put(
                CalendarContract.Events.DESCRIPTION,
                listOf(timetable.name, course.teacher, "由纸间课表写入")
                    .filter { it.isNotBlank() }.joinToString(" · "),
            )
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        runCatching { context.contentResolver.insert(eventsUri(authority), values) }
    }
}
