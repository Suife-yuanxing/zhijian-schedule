package com.zhijian.schedule.core.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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

    /**
     * FGS 骨架通知：前台服务存活的载体，随服务常驻。非实时态（下节课/已结束/假期）由它承载
     * 用户可见状态；实时态退为一行静默说明。系统对 FGS 通知豁免 timeoutAfter（撤销它会违反
     * 前台服务契约，撤了也保不住），所以实时卡片刻意与它分离——见 [LIVE_CARD_ID]。
     */
    const val SERVICE_NOTIFICATION_ID = 3000

    /**
     * 实时卡片（课程进行中 / 即将上课）：请求提升为流体云，并带系统级 timeoutAfter——
     * 进程冻结、切换闹钟被 ROM 吞掉时（2026-10-16 21:35 事故：卡片停在「正在上课 00:00」
     * 47 分钟直到打开 App），由 system_server 在倒计时跑完后准点撤下，不依赖 App 被唤醒。
     * 必须是非 FGS 通知：FGS 通知被系统豁免 timeout，永远撤不下来。
     */
    const val LIVE_CARD_ID = 3001

    /** 请求系统提升为 Live Update / 流体云胶囊（Android 16 正式 API 的 extra 键） */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /** 「即将上课」状态用的品牌陶土色，与上课中的课程色区分开 */
    private const val COLOR_ABOUT_TO_START = 0xFFD97757.toInt()

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
        baseBuilder(context, "纸间课表", "实时胶囊启动中…", chip = "", promote = false, color = 0, countdownTo = null)
            .build()

    /** 一次渲染的结果；骨架通知内容未变更时 [serviceNotification] 为 null（不重复抢发） */
    class Posted(
        /** 实时卡片内容 key；非实时态为 null（卡片已撤下） */
        val cardKey: String?,
        /** 骨架通知内容 key */
        val skeletonKey: String,
        /** 本次新构建的骨架通知（startForeground 复用），未变更时为 null */
        val serviceNotification: Notification?,
        /** 当前是否处于「即将上课 / 上课中」实时态，服务据此持有 wake lock */
        val live: Boolean,
    )

    /**
     * 渲染并推送两条通知：实时卡片（可提升、系统兜底退场）+ FGS 骨架（常驻）。
     * 与上次内容相同时按各自的 key 跳过刷新。
     */
    fun post(
        context: Context,
        plan: CapsulePlan,
        previousCardKey: String?,
        previousSkeletonKey: String?,
    ): Posted {
        val nm = context.getSystemService(NotificationManager::class.java)
        // ---- 实时卡片：独立通知（非 FGS），timeout 到点由系统撤下 ----
        val live = plan.isLive
        val cardKey: String? = if (live) {
            val (title, text) = texts(plan)
            val progress = progressOf(plan)
            // chip 进 key：每分钟的「剩 X 分」变化也要触发刷新
            val key = "$title|$text|${plan.chip}|${progress ?: -1}"
            if (key != previousCardKey) {
                val builder = baseBuilder(
                    context, title, text, plan.chip,
                    promote = true,
                    color = courseColorOf(plan),
                    countdownTo = countdownTargetOf(plan),
                    smallIcon = smallIconOf(plan),
                    subtext = courseSubText(plan),
                )
                if (progress != null) {
                    if (Build.VERSION.SDK_INT >= 36) {
                        applyProgressStyle(builder, plan, progress)
                    } else {
                        builder.setProgress(100, progress, false)
                    }
                }
                // 系统级兜底：倒计时跑完 + 宽限后由 system_server 撤下（进程冻结/闹钟被吞也不卡死）
                countdownCleanupDelayMillis(plan, System.currentTimeMillis())
                    ?.let { builder.setTimeoutAfter(it) }
                runCatching { nm?.notify(LIVE_CARD_ID, builder.build()) }
            }
            key
        } else {
            runCatching { nm?.cancel(LIVE_CARD_ID) }
            null
        }
        // ---- FGS 骨架：非实时态承载状态文本；实时态让位给实时卡片 ----
        val (skelTitle, skelText, skelChip) = skeletonTexts(plan)
        val skeletonKey = "$skelTitle|$skelText|${if (live) "" else courseSubText(plan).orEmpty()}"
        val serviceNotification = if (skeletonKey != previousSkeletonKey) {
            val skeleton = baseBuilder(
                context, skelTitle, skelText, skelChip,
                promote = false, color = 0, countdownTo = null,
                subtext = if (live) null else courseSubText(plan),
            ).build()
            runCatching { nm?.notify(SERVICE_NOTIFICATION_ID, skeleton) }
            skeleton
        } else null
        return Posted(cardKey, skeletonKey, serviceNotification, live)
    }

    /** 骨架通知文案：非实时态与之前一致（它就是用户看到的状态卡）；实时态退为静默说明 */
    private fun skeletonTexts(plan: CapsulePlan): Triple<String, String, String> =
        if (plan.isLive) {
            Triple("纸间课表", "课程胶囊运行中 · 打开今日课表", "")
        } else {
            val (title, text) = texts(plan)
            Triple(title, text, plan.chip)
        }

    /**
     * 倒计时目标：上课中→下课，即将上课→上课。
     * 由系统 chronometer 每秒自绘，不依赖本进程刷新——ColorOS 冻结后台进程时仍持续走秒。
     */
    private fun countdownTargetOf(plan: CapsulePlan): Long? = when (plan) {
        is CapsulePlan.Ongoing -> plan.endAtMillis
        is CapsulePlan.AboutToStart -> plan.startAtMillis
        else -> null
    }

    fun texts(plan: CapsulePlan): Pair<String, String> = when (plan) {
        is CapsulePlan.Ongoing -> {
            val c = plan.course
            val where = listOf(c.room, c.teacher).filter { it.isNotBlank() }
                .joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " }
            val reminder = plan.reminderMinutes
            if (reminder != null) {
                "距离下课仅剩 $reminder 分钟" to
                    "${c.name} · $where${TimeUtils.formatTime(c.end)} 下课"
            } else {
                // 开始时间已过，给下课时刻更实用
                val tail = if (plan.remainingMinutes <= 0) "即将下课"
                    else "${TimeUtils.formatTime(c.end)} 下课 · 还剩 ${plan.remainingMinutes} 分钟"
                "正在上课 · ${c.name}" to "$where$tail"
            }
        }

        is CapsulePlan.AboutToStart -> {
            val c = plan.course
            val where = listOf(c.room, c.teacher).filter { it.isNotBlank() }
                .joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " }
            val left = if (plan.minutesUntil <= 0) "马上开始" else "还有 ${TimeUtils.minutesText(plan.minutesUntil)}"
            "即将上课 · ${c.name}" to
                "$where${TimeUtils.formatTime(c.start)} · $left"
        }

        is CapsulePlan.Upcoming -> {
            val c = plan.course
            val where = listOf(c.room, c.teacher).filter { it.isNotBlank() }
                .joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " }
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

    /**
     * 上课中按剩余进度逐块消耗小图标；即将上课尚未开课，保持满格。
     * 提醒节点把暗掉的课块重新点亮，让状态栏图标也能看出状态切换。
     */
    private fun smallIconOf(plan: CapsulePlan): Int = when {
        plan is CapsulePlan.Ongoing && plan.reminderMinutes != null -> R.drawable.ic_stat_notify
        plan is CapsulePlan.Ongoing -> consumedIcon(plan.progress)
        else -> R.drawable.ic_stat_notify
    }

    /**
     * 每消耗 1/4 课堂，按课表阅读顺序（左上→右上→左下→右下）暗掉一块课块，
     * 让流体云/状态栏上的图标像进度条一样被「用完」。
     */
    private fun consumedIcon(progress: Int): Int = when {
        progress >= 75 -> R.drawable.ic_stat_notify
        progress >= 50 -> R.drawable.ic_stat_notify_used1
        progress >= 25 -> R.drawable.ic_stat_notify_used2
        else -> R.drawable.ic_stat_notify_used3
    }

    /** 状态色区分：上课中用课程色，提醒节点与即将上课用品牌陶土色，一眼可辨 */
    private fun courseColorOf(plan: CapsulePlan): Int = when (plan) {
        is CapsulePlan.Ongoing ->
            if (plan.reminderMinutes != null) COLOR_ABOUT_TO_START else plan.course.color
        is CapsulePlan.AboutToStart -> COLOR_ABOUT_TO_START
        else -> 0
    }

    /**
     * 通知副标题：节次 · 教室 · 上课时间段 · 教师。
     * ColorOS 提升卡片不显示 contentText，副标题是卡面上唯一能补充信息的文本行。
     */
    private fun courseSubText(plan: CapsulePlan): String? {
        val course = when (plan) {
            is CapsulePlan.Ongoing -> plan.course
            is CapsulePlan.AboutToStart -> plan.course
            is CapsulePlan.Upcoming -> plan.course
            else -> null
        } ?: return null
        val parts = buildList {
            course.sectionText?.takeIf { it.isNotBlank() }?.let(::add)
            course.room.takeIf { it.isNotBlank() }?.let(::add)
            add("${TimeUtils.formatTime(course.start)}-${TimeUtils.formatTime(course.end)}")
            course.teacher.takeIf { it.isNotBlank() }?.let(::add)
        }
        return parts.joinToString(" · ")
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
        countdownTo: Long?,
        smallIcon: Int = R.drawable.ic_stat_notify,
        subtext: String? = null,
    ): Notification.Builder {
        val builder = Notification.Builder(context, CH_CAPSULE)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (subtext != null) builder.setSubText(subtext)
        if (countdownTo != null) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(countdownTo)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= 36 && chip.isNotEmpty()) {
            builder.setShortCriticalText(chip)
            if (promote) {
                if (color != 0) builder.setColor(color)
                builder.addAction(openTodayAction(context))
                // 提升资格（ui_rich_ongoing 生效时）：显式请求 + 常驻 + 标题 + ProgressStyle，
                // 且不得着色/分组摘要/自定义视图；另需用户在系统里为该应用放行实时通知
                builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            }
        }
        return builder
    }

    /** 卡片/通知上的「打开今日课表」操作按钮 */
    private fun openTodayAction(context: Context): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_notify),
            context.getString(R.string.capsule_action_open_today),
            contentIntent(context),
        ).build()

    @RequiresApi(36)
    private fun applyProgressStyle(builder: Notification.Builder, plan: CapsulePlan, progress: Int) {
        val style = Notification.ProgressStyle()
            .setProgress(progress.coerceIn(0, 100))
            .addProgressSegment(
                Notification.ProgressStyle.Segment(100).setColor(courseColorOf(plan))
            )
        builder.setStyle(style)
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            // 独立 requestCode：避免与站内其它 MainActivity PendingIntent（requestCode 0）
            // 因 extras 合并规则互相覆盖
            LIVE_CARD_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
