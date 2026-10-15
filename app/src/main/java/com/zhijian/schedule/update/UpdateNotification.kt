package com.zhijian.schedule.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zhijian.schedule.MainActivity
import com.zhijian.schedule.R

/**
 * 更新下载的通知：dataSync 前台服务的进度通知（带「取消下载」动作）+ 一条完成通知。
 * 渠道与课程胶囊（capsule）隔离，用户关掉胶囊不会连带丢掉下载进度。
 */
object UpdateNotification {

    const val CH_UPDATE = "update"
    const val NOTIFICATION_ID_PROGRESS = 3101
    const val NOTIFICATION_ID_DONE = 3102
    const val ACTION_CANCEL = "com.zhijian.schedule.update.action.CANCEL"

    /** IMPORTANCE_LOW：不响铃不震动，只在通知栏安静走进度 */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_UPDATE,
                context.getString(R.string.channel_update_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_update_desc) }
        )
    }

    /** 进度通知；percent 为 null 时显示不确定进度（响应没有长度信息） */
    fun buildProgress(
        context: Context,
        version: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        percent: Int?,
        etaSeconds: Long?,
    ): Notification {
        val text = if (totalBytes != null) {
            val tail = ResumableDownload.etaText(etaSeconds)?.let { " · $it" }.orEmpty()
            "${ResumableDownload.formatBytes(downloadedBytes)} / " +
                "${ResumableDownload.formatBytes(totalBytes)}$tail"
        } else {
            "已下载 ${ResumableDownload.formatBytes(downloadedBytes)}"
        }
        return NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在下载更新 v$version")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.update_action_cancel), cancelDownloadIntent(context))
            .build()
    }

    /** 更新前台服务那条通知（同一个 id，重复 notify 会原地替换） */
    fun postProgress(context: Context, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    /**
     * 下载完成、校验通过后的通知。点按只回 App —— 安装前的「未知来源」引导留在设置页卡片里做，
     * 免得在通知里跳系统页体验割裂。
     */
    fun postDone(context: Context, version: String) {
        val notification = NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("更新已下载")
            .setContentText("点按返回应用完成安装（v$version）")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DONE, notification)
        }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS) }
    }

    fun cancelDone(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_DONE) }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_PROGRESS,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelDownloadIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            NOTIFICATION_ID_PROGRESS + 1,
            Intent(context, ApkDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
