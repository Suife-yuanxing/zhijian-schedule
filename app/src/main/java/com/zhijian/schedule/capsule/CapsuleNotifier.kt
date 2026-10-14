package com.zhijian.schedule.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.zhijian.schedule.MainActivity
import com.zhijian.schedule.R
import com.zhijian.schedule.core.util.TimeUtils

/**
 * 课程实时胶囊的通知构建。
 * Android 16+ 用 ProgressStyle 通知 + `android.requestPromotedOngoing` extra 请求系统提升为
 * Live Update（ColorOS 16 显示为状态栏顶部「流体云」胶囊，需用户在系统通知设置里为
 * 本应用开启「流体云显示实时活动」）；低版本退化为普通常驻进度通知。
 * 提升只在「即将上课（课前提醒倒数窗口）」与「正在上课」两个状态请求。
 */
object CapsuleNotifier {

    const val CH_CAPSULE = "capsule"
    const val NOTIFICATION_ID = 3001

    /** 请求系统提升为 Live Update / 流体云胶囊（Android 16 正式 API 的 extra 键） */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_CAPSULE,
                context.getString(R.string.channel_capsule_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_capsule_desc) }
        )
    }

    /**
     * 系统是否放行本应用的实时活动（ColorOS「流体云显示实时活动」/ 原生「实时通知」开关）。
     * 关闭时通知仍会发布，但系统不会提升为流体云；返回 null 表示系统不支持查询。
     */
    fun canPromote(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications()
            }.getOrNull()
        } else null

    fun placeholder(context: Context): Notification =
        baseBuilder(context, "纸间课表", "实时胶囊启动中…", chip = "", promote = false, color = 0)
            .build()

    /** 渲染并推送通知；返回本次内容 key，与上次相同时跳过刷新 */
    fun post(context: Context, plan: CapsulePlan, previousKey: String?): String {
        val (title, text) = texts(plan)
        val progress = progressOf(plan)
        val key = "$title|$text|${progress ?: -1}"
        if (key == previousKey) return key

        val promote = isPromotable(plan)
        val builder = baseBuilder(
            context, title, text, plan.chip,
            promote = promote,
            color = if (promote) courseColorOf(plan) else 0,
        )
        if (progress != null) {
            if (Build.VERSION.SDK_INT >= 36) {
                applyProgressStyle(builder, plan, progress)
            } else {
                builder.setProgress(100, progress, false)
            }
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, builder.build())
        }
        return key
    }

    fun texts(plan: CapsulePlan): Pair<String, String> = when (plan) {
        is CapsulePlan.Ongoing -> {
            val c = plan.course
            val where = if (c.room.isBlank()) "" else "${c.room} · "
            val remaining = if (plan.remainingMinutes <= 0) "即将下课" else "还剩 ${plan.remainingMinutes} 分钟"
            "正在上课 · ${c.name}" to
                "$where${TimeUtils.formatTime(c.start)}-${TimeUtils.formatTime(c.end)} · $remaining"
        }

        is CapsulePlan.AboutToStart -> {
            val c = plan.course
            val where = if (c.room.isBlank()) "" else "${c.room} · "
            val left = if (plan.minutesUntil <= 0) "马上开始" else "还有 ${TimeUtils.minutesText(plan.minutesUntil)}"
            "即将上课 · ${c.name}" to
                "$where${TimeUtils.formatTime(c.start)} · $left"
        }

        is CapsulePlan.Upcoming -> {
            val c = plan.course
            val where = if (c.room.isBlank()) "" else "${c.room} · "
            val until = if (plan.minutesUntil <= 0) "马上开始" else "${TimeUtils.minutesText(plan.minutesUntil)}后"
            "下节课 · ${c.name}" to
                "$where${TimeUtils.formatTime(c.start)} · $until"
        }

        is CapsulePlan.Holiday -> {
            val text = plan.nextText?.let { "今天放假 · 下一节 $it" } ?: "今天放假，好好休息一下"
            "假期 · ${plan.name}" to text
        }

        is CapsulePlan.DayDone -> {
            val title = if (plan.hadClassesToday) "今日课程已结束" else "今天没有课"
            val text = plan.nextText?.let { "下一节 · $it" } ?: "本周暂无课程安排"
            title to text
        }

        CapsulePlan.NoTable -> "纸间课表" to "还没有课表，先导入或手动创建一张"
    }

    /** 只有「即将上课/上课中」的实时状态才请求提升为胶囊 */
    private fun isPromotable(plan: CapsulePlan): Boolean =
        plan is CapsulePlan.Ongoing || plan is CapsulePlan.AboutToStart

    private fun courseColorOf(plan: CapsulePlan): Int = when (plan) {
        is CapsulePlan.Ongoing -> plan.course.color
        is CapsulePlan.AboutToStart -> plan.course.color
        else -> 0
    }

    private fun progressOf(plan: CapsulePlan): Int? = when (plan) {
        is CapsulePlan.Ongoing -> plan.progress
        is CapsulePlan.AboutToStart -> plan.progress
        else -> null
    }

    private fun baseBuilder(
        context: Context,
        title: String,
        text: String,
        chip: String,
        promote: Boolean,
        color: Int,
    ): Notification.Builder {
        val builder = Notification.Builder(context, CH_CAPSULE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 36 && chip.isNotEmpty()) {
            builder.setShortCriticalText(chip)
            if (promote) {
                if (color != 0) builder.setColor(color)
                // 提升资格（ui_rich_ongoing 生效时）：显式请求 + 常驻 + 标题 + ProgressStyle，
                // 且不得着色/分组摘要/自定义视图；另需用户在系统里为该应用放行实时通知
                builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            }
        }
        return builder
    }

    @RequiresApi(36)
    private fun applyProgressStyle(builder: Notification.Builder, plan: CapsulePlan, progress: Int) {
        val color = when (plan) {
            is CapsulePlan.Ongoing -> plan.course.color
            is CapsulePlan.AboutToStart -> plan.course.color
            else -> 0
        }
        val style = Notification.ProgressStyle()
            .setProgress(progress.coerceIn(0, 100))
            .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(color))
        builder.setStyle(style)
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            // 独立 requestCode：避免与站内其它 MainActivity PendingIntent（requestCode 0）
            // 因 extras 合并规则互相覆盖
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
