package com.zhijian.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

/** 考前提醒触发：数据随 intent 携带，无需回查数据库（考试时刻固定，无需续排） */
@AndroidEntryPoint
class ExamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val examId = intent.getLongExtra(EXTRA_EXAM_ID, -1L)
        if (examId <= 0) return
        Notifications.showExamReminder(
            context = context,
            examId = examId,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            room = intent.getStringExtra(EXTRA_ROOM).orEmpty(),
            seat = intent.getStringExtra(EXTRA_SEAT).orEmpty(),
            leadMinutes = intent.getIntExtra(EXTRA_LEAD, 60),
        )
    }

    companion object {
        const val EXTRA_EXAM_ID = "exam_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_SEAT = "seat"
        const val EXTRA_LEAD = "lead"
    }
}
