package com.zhijian.schedule.core.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课程写入系统日历（方案 6.5）：
 * ColorOS 新版日历私有库 com.coloros.calendar，字段与 CalendarContract 一致，
 * 旧版回退原生 com.android.calendar。
 */
@Singleton
class CalendarSyncManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsStore: SettingsStore,
    ) {
        companion object {
            private const val AUTHORITY_COLOROS = "com.coloros.calendar"
            private const val AUTHORITY_AOSP = "com.android.calendar"
            private const val ACCOUNT_NAME = "纸间课表"
            private const val CALENDAR_NAME = "纸间课表"
        }

        /** 手动开关同步与协调器自动重刷可能并发，串行化避免「删+插」交错写出重复事件 */
        private val syncMutex = Mutex()

        fun hasPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * 日历 Provider 是否存在。查询日历数据需要 READ_CALENDAR，未授权时
         * resolveAuthority() 必然失败，只能改用 PackageManager 判断。
         */
        fun hasCalendarProvider(): Boolean {
            val manager = context.packageManager
            return listOf(AUTHORITY_COLOROS, AUTHORITY_AOSP).any { authority ->
                runCatching { manager.resolveContentProvider(authority, 0) }.getOrNull() != null
            }
        }

        /** 探测可用的日历 Provider（ColorOS 私有库优先） */
        fun resolveAuthority(): String? {
            for (authority in listOf(AUTHORITY_COLOROS, AUTHORITY_AOSP)) {
                val ok =
                    runCatching {
                        context.contentResolver
                            .query(
                                calendarsUri(authority),
                                arrayOf(CalendarContract.Calendars._ID),
                                null,
                                null,
                                null,
                            )?.use { true } ?: false
                    }.getOrDefault(false)
                if (ok) return authority
            }
            return null
        }

        /** 用户手动开关：无条件整表重刷 */
        suspend fun syncAll(
            courses: List<CourseEntity>,
            timetable: TimetableEntity,
            sections: List<SectionTimeEntity>,
        ): Result<Int> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) { writeEvents(courses, timetable, sections) }
            }

        /** 数据变化触发的自动重刷：内容与日历状态均未变时返回 null，不产生写入 */
        suspend fun syncIfNeeded(
            courses: List<CourseEntity>,
            timetable: TimetableEntity,
            sections: List<SectionTimeEntity>,
        ): Result<Int>? =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    if (!needsSync(courses, timetable, sections)) return@withContext null
                    writeEvents(courses, timetable, sections)
                }
            }

        suspend fun clearAll(): Result<Int> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val authority =
                            resolveAuthority()
                                ?: throw IllegalStateException("未找到系统日历")
                        val calendarId = findCalendar(authority)
                        if (calendarId == null) {
                            0
                        } else {
                            deleteEvents(authority, calendarId)
                        }
                    }.onSuccess { settingsStore.setCalendarFingerprint("") }
                }
            }

        private suspend fun needsSync(
            courses: List<CourseEntity>,
            timetable: TimetableEntity,
            sections: List<SectionTimeEntity>,
        ): Boolean {
            val stored = settingsStore.calendarFingerprint()
            if (stored.isNullOrEmpty() || stored != fingerprint(courses, timetable, sections)) return true
            // 用户清空过日历数据时日历本体已不存在，需要重建（指纹还在 DataStore 里）
            val authority = resolveAuthority() ?: return true
            return findCalendar(authority) == null
        }

        private suspend fun writeEvents(
            courses: List<CourseEntity>,
            timetable: TimetableEntity,
            sections: List<SectionTimeEntity>,
        ): Result<Int> =
            runCatching {
                val authority =
                    resolveAuthority()
                        ?: throw IllegalStateException("未找到系统日历")
                val calendarId =
                    ensureCalendar(authority)
                        ?: throw IllegalStateException("无法创建日历（请检查日历权限）")
                deleteEvents(authority, calendarId)

                val startDate =
                    TimeUtils.parseDate(timetable.startDate)
                        ?: throw IllegalStateException("开学日期无效")
                var count = 0
                courses.forEach { course ->
                    val startText = sections.getOrNull(course.startSection - 1)?.startTime
                    val endText = sections.getOrNull(course.endSection - 1)?.endTime
                    val startTime = TimeUtils.parseTime(startText) ?: return@forEach
                    val endTime = TimeUtils.parseTime(endText) ?: startTime.plusMinutes(45)
                    WeekMask.weeks(course.weekMask).forEach { week ->
                        val date = TimeUtils.dateOfWeekDay(startDate, week, course.dayOfWeek)
                        val begin =
                            LocalDateTime
                                .of(date, startTime)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        val finish =
                            LocalDateTime
                                .of(date, endTime)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        insertEvent(authority, calendarId, course, timetable, begin, finish)
                        count++
                    }
                }
                count
            }.onSuccess {
                runCatching { settingsStore.setCalendarFingerprint(fingerprint(courses, timetable, sections)) }
            }

        /** 摘要所有会进入日历事件的字段；含版本号，升级后自动重刷一次以应用新格式 */
        private fun fingerprint(
            courses: List<CourseEntity>,
            timetable: TimetableEntity,
            sections: List<SectionTimeEntity>,
        ): String {
            val canonical =
                buildString {
                    append(BuildConfig.VERSION_NAME).append('\n')
                    append(timetable.name).append('|').append(timetable.startDate).append('\n')
                    sections.forEach { append(it.startTime).append('|').append(it.endTime).append('\n') }
                    courses.sortedBy { it.id }.forEach {
                        append(it.name)
                            .append('|')
                            .append(it.room)
                            .append('|')
                            .append(it.teacher)
                            .append('|')
                            .append(it.dayOfWeek)
                            .append('|')
                            .append(it.startSection)
                            .append('|')
                            .append(it.endSection)
                            .append('|')
                            .append(it.weekMask)
                            .append('\n')
                    }
                }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun calendarsUri(authority: String) = android.net.Uri.parse("content://$authority/calendars")

        private fun eventsUri(authority: String) = android.net.Uri.parse("content://$authority/events")

        private fun findCalendar(authority: String): Long? {
            context.contentResolver
                .query(
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
            val uri =
                calendarsUri(authority)
                    .buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    .build()
            val values =
                ContentValues().apply {
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
            val inserted =
                runCatching {
                    context.contentResolver.insert(uri, values)
                }.getOrNull() ?: return null
            return runCatching { ContentUris.parseId(inserted) }.getOrNull()
        }

        private fun deleteEvents(
            authority: String,
            calendarId: Long,
        ): Int =
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
            val values =
                ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, course.name)
                    put(CalendarContract.Events.EVENT_LOCATION, course.room)
                    put(
                        CalendarContract.Events.DESCRIPTION,
                        listOf(timetable.name, course.teacher, "由纸间课表写入")
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
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
