package com.zhijian.schedule.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课前精确提醒（方案 4.7）：AlarmManager.setExactAndAllowWhileIdle，
 * 每次触发后自动续排该课程的下一次（滚动链），应用启动 / 数据变更时全量重排。
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager().canScheduleExactAlarms() else true

    /** 全量重排：取消后按下一周内（滚动链会自动延续）重新登记 */
    fun rescheduleAll(
        courses: List<CourseEntity>,
        timetable: TimetableEntity,
        sections: List<SectionTimeEntity>,
        reminderEnabled: Boolean,
        reminderMinutes: Int,
    ) {
        courses.forEach { cancel(it.id) }
        if (!reminderEnabled) return
        val now = LocalDateTime.now()
        courses.forEach { course ->
            nextOccurrence(course, timetable, sections, now, reminderMinutes)?.let { remindAt ->
                schedule(course, remindAt, sections, reminderMinutes)
            }
        }
    }

    /** 单课程续排（提醒触发后调用） */
    fun rescheduleCourse(
        course: CourseEntity,
        timetable: TimetableEntity,
        sections: List<SectionTimeEntity>,
        reminderMinutes: Int,
        after: LocalDateTime,
    ) {
        nextOccurrence(course, timetable, sections, after, reminderMinutes)?.let { remindAt ->
            schedule(course, remindAt, sections, reminderMinutes)
        }
    }

    private fun schedule(
        course: CourseEntity,
        remindAt: LocalDateTime,
        sections: List<SectionTimeEntity>,
        reminderMinutes: Int,
    ) {
        val classStart = remindAt.plusMinutes(reminderMinutes.toLong())
        val startTimeText = sections.getOrNull(course.startSection - 1)?.startTime.orEmpty()
        val triggerAt = remindAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val manager = alarmManager()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_COURSE_ID, course.id)
            putExtra(ReminderReceiver.EXTRA_COURSE_NAME, course.name)
            putExtra(ReminderReceiver.EXTRA_ROOM, course.room)
            putExtra(ReminderReceiver.EXTRA_MINUTES, reminderMinutes)
            putExtra(ReminderReceiver.EXTRA_START_TIME, startTimeText)
            putExtra(ReminderReceiver.EXTRA_CLASS_TIME, classStart.toString())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            course.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            // 未授予精确闹钟权限时退化为非精确（仍会在 Doze 允许窗口内送达）
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(courseId: Long) {
        // requestCode 与 courseId 绑定，extras 不参与 PendingIntent 匹配，可直接找回
        val pi = PendingIntent.getBroadcast(
            context,
            courseId.toInt(),
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager().cancel(it) }
    }

    fun cancelAll(courses: List<CourseEntity>) {
        courses.forEach { cancel(it.id) }
    }

    /**
     * 计算课程下一次「提醒时刻」（上课时间 - 提前分钟数）。
     */
    fun nextOccurrence(
        course: CourseEntity,
        timetable: TimetableEntity,
        sections: List<SectionTimeEntity>,
        from: LocalDateTime,
        reminderMinutes: Int,
    ): LocalDateTime? {
        val startDate = TimeUtils.parseDate(timetable.startDate) ?: return null
        val startTime: LocalTime = sections.getOrNull(course.startSection - 1)
            ?.let { TimeUtils.parseTime(it.startTime) } ?: return null
        WeekMask.weeks(course.weekMask).forEach { week ->
            val date: LocalDate = TimeUtils.dateOfWeekDay(startDate, week, course.dayOfWeek)
            val remindAt = LocalDateTime.of(date, startTime).minusMinutes(reminderMinutes.toLong())
            if (remindAt.isAfter(from)) return remindAt
        }
        return null
    }
}
