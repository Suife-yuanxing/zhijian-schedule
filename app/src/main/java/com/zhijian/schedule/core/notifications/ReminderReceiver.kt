package com.zhijian.schedule.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: ScheduleRepository

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, -1L)
        val name = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 15)
        val startTime = intent.getStringExtra(EXTRA_START_TIME).orEmpty()
        if (courseId <= 0) return

        Notifications.showClassReminder(
            context = context,
            notificationId = courseId.toInt(),
            courseName = name,
            room = room,
            minutesLeft = minutes,
            startTime = startTime,
        )

        // 滚动续排该课程的下一次
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val settings = repository.settings.first()
                if (settings.reminderEnabled) {
                    val course = repository.getCourse(courseId)
                    val timetable = repository.activeTimetable.first()
                    val sections = repository.activeSections.first()
                    if (course != null && timetable != null) {
                        alarmScheduler.rescheduleCourse(
                            course = course,
                            timetable = timetable,
                            sections = sections,
                            reminderMinutes = settings.reminderMinutes,
                            after = java.time.LocalDateTime.now(),
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_CLASS_TIME = "class_time"
    }
}
