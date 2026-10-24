package com.zhijian.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhijian.schedule.core.data.db.ExamEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 考前精确提醒（两档，见 ExamAlarms）：考试是固定时刻、无需滚动续排，
 * 全量重排即可；考试的增删改由 ExamReminderCoordinator 监听触发重排。
 */
@Singleton
class ExamAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 当前由本调度器托管的考试 id：全量重排时据此撤销已删除考试的残留闹钟 */
    private val managedIds = mutableSetOf<Long>()

    @Synchronized
    fun rescheduleAll(exams: List<ExamEntity>, now: LocalDateTime = LocalDateTime.now()) {
        val exactAllowed = canScheduleExact()
        val keepIds = exams.mapTo(mutableSetOf()) { it.id }
        managedIds.filter { it !in keepIds }.forEach { cancel(it) }
        managedIds.clear()
        managedIds.addAll(keepIds)
        exams.forEach { exam ->
            ExamAlarms.slots(exam, now).forEachIndexed { index, slot ->
                schedule(exam, slot, index, exactAllowed)
            }
        }
    }

    @Synchronized
    fun cancel(examId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        (ExamAlarms.LEAD_MINUTES.indices).forEach { slot ->
            val pi = PendingIntent.getBroadcast(
                context,
                ExamAlarms.requestCode(examId, slot),
                Intent(context, ExamReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pi?.let { manager.cancel(it) }
        }
    }

    private fun schedule(exam: ExamEntity, slot: ExamAlarms.Slot, slotIndex: Int, exactAllowed: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = slot.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            putExtra(ExamReminderReceiver.EXTRA_EXAM_ID, exam.id)
            putExtra(ExamReminderReceiver.EXTRA_LEAD, slot.leadMinutes)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            ExamAlarms.requestCode(exam.id, slotIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (exactAllowed) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
}
