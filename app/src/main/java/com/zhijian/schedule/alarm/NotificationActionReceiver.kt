package com.zhijian.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** 提醒通知的「静默」操作 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id > 0) {
                runCatching { NotificationManagerCompat.from(context).cancel(id) }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.zhijian.schedule.action.DISMISS_REMINDER"
        const val EXTRA_ID = "notification_id"
    }
}
