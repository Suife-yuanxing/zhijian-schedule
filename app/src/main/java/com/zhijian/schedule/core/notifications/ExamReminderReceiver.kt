package com.zhijian.schedule.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhijian.schedule.core.data.db.ExamDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 考前提醒触发；考试时刻固定无需续排，但需防幽灵闹钟（考试已删而闹钟未及撤销） */
@AndroidEntryPoint
class ExamReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var examDao: ExamDao

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val examId = intent.getLongExtra(EXTRA_EXAM_ID, -1L)
        if (examId <= 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val exam = examDao.getById(examId) ?: return@launch
                Notifications.showExamReminder(
                    context = context,
                    examId = exam.id,
                    name = exam.name,
                    room = exam.room,
                    seat = exam.seat,
                    leadMinutes = intent.getIntExtra(EXTRA_LEAD, 60),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EXAM_ID = "exam_id"
        const val EXTRA_LEAD = "lead"
    }
}
