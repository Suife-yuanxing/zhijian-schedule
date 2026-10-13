package com.zhijian.schedule.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zhijian.schedule.MainActivity
import com.zhijian.schedule.R

object Notifications {

    const val CH_REMINDER = "reminder"
    const val CH_SYNC = "sync"
    const val CH_EXAM = "exam"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_REMINDER,
                context.getString(R.string.channel_reminder_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_reminder_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_SYNC,
                context.getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_sync_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_EXAM,
                context.getString(R.string.channel_exam_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_exam_desc) }
        )
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        subText: String? = null,
    ) {
        if (!canNotify(context)) return
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun showClassReminder(
        context: Context,
        notificationId: Int,
        courseName: String,
        room: String,
        minutesLeft: Int,
        startTime: String,
    ) {
        if (!canNotify(context)) return
        val where = if (room.isBlank()) "" else " · $room"
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DISMISS
                putExtra(NotificationActionReceiver.EXTRA_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("$startTime 上课 · $courseName")
            .setContentText("${if (minutesLeft > 0) "$minutesLeft 分钟后上课" else "即将上课"}$where")
            .setSubText("纸间课表")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${if (minutesLeft > 0) "$minutesLeft 分钟后上课" else "即将上课"}$where"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "静默", dismissIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    fun showSyncChanged(context: Context, summary: String) {
        show(
            context = context,
            id = 2001,
            channel = CH_SYNC,
            title = "课表已自动更新",
            text = summary,
        )
    }

    fun showSyncFailed(context: Context, needsLogin: Boolean) {
        show(
            context = context,
            id = 2002,
            channel = CH_SYNC,
            title = "课表刷新失败",
            text = if (needsLogin) "登录会话已过期，点击打开 App 重新登录教务" else "点击打开 App 手动刷新",
        )
    }
}
