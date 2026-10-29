package com.zhijian.schedule.core.capsule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhijian.schedule.MainActivity
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 胶囊状态机：读取数据 → 构建状态 → 渲染通知 → 登记下一次状态切换的精确闹钟。
 *
 * 状态切换（进入课前提醒窗口、上课、下课、跨天）不能只靠服务里的刷新循环：
 * ColorOS 冻结后台进程时循环停摆，胶囊会停在旧状态，课前提醒时刻流体云不会切换。
 * 因此每次渲染后用 AlarmManager 精确闹钟登记下一次切换时刻，进程被冻结/杀死
 * 也由系统按时唤醒重算，让「即将上课」倒计时准时出现。
 *
 * 真机事故（2026-10-16）：14:20 的上课切换闹钟丢失后，胶囊停在「即将上课 00:00」
 * 直到用户打开 App——单次丢失即全天卡死。因此每次渲染同时登记一个**心跳闹钟**
 * （独立 PendingIntent，见 [nextRefreshAtMillis]）：实时态每 6 分钟保鲜、切换丢失
 * 1 分钟内自愈，非实时态切换丢失 2 分钟内自愈。
 *
 * ColorOS 还会把普通精确闹钟降级成 +1h 窗口。默认开启「系统闹钟兜底」：
 * 临近切换时改用 `setAlarmClock`（系统闹钟级 API，Doze 也准点，状态栏短暂显示
 * 闹钟图标）；用户配置好后台运行后可在设置里关闭它，恢复无图标的精确闹钟。
 */
@Singleton
class CapsuleCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val holidayDao: HolidayDao,
    ) {
        /** 渲染当前胶囊并登记下一次切换闹钟；胶囊功能关闭时撤销闹钟并返回 null */
        suspend fun renderNow(
            previousCardKey: String? = null,
            previousSkeletonKey: String? = null,
        ): CapsuleNotifier.Posted? {
            val settings = settingsStore.settings.first()
            if (!settings.capsuleEnabled) {
                cancelTransition()
                return null
            }
            val now = LocalDateTime.now()
            val plan =
                buildCapsulePlan(
                    timetable = repository.activeTimetable.first(),
                    courses = repository.activeCourses.first(),
                    sections = repository.activeSections.first(),
                    now = now,
                    reminderMinutes = if (settings.reminderEnabled) settings.reminderMinutes else 0,
                    holidayName = todayHolidayName(now),
                    exams = repository.activeExams.first(),
                )
            val posted = CapsuleNotifier.post(context, plan, previousCardKey, previousSkeletonKey)
            scheduleNextTransition(plan, now, settings.capsuleAlarmFallbackEnabled)
            return posted
        }

        /** 胶囊关闭（或不再需要）时撤销待触发的切换闹钟（含心跳） */
        fun cancelTransition() {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            manager.cancel(transitionPendingIntent())
            manager.cancel(beatPendingIntent())
        }

        private suspend fun todayHolidayName(now: LocalDateTime): String? =
            holidayDao
                .observeAll()
                .first()
                .firstOrNull { it.date == now.toLocalDate().toString() && it.isOffDay }
                ?.name

        private fun scheduleNextTransition(
            plan: CapsulePlan,
            now: LocalDateTime,
            alarmFallback: Boolean,
        ) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduleAt(
                manager,
                nextTransitionAtMillis(plan, now),
                transitionPendingIntent(),
                isTransition = true,
                alarmFallback,
                nowMillis,
            )
            scheduleAt(
                manager,
                nextRefreshAtMillis(plan, now),
                beatPendingIntent(),
                isTransition = false,
                alarmFallback,
                nowMillis,
            )
        }

        private fun scheduleAt(
            manager: AlarmManager?,
            at: Long?,
            pendingIntent: PendingIntent,
            isTransition: Boolean,
            alarmFallback: Boolean,
            nowMillis: Long,
        ) {
            if (manager == null) return
            if (at == null || at <= nowMillis) {
                manager.cancel(pendingIntent)
                return
            }
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (!exactAllowed) {
                // 未授予精确闹钟权限时退化为非精确（与课前提醒同一策略）
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
                return
            }
            // 临近切换用 setAlarmClock 兜底：ColorOS 会冻结进程并把普通精确闹钟降级成 +1h 窗口，
            // 只有闹钟级 API 能准点唤醒；代价是状态栏短暂出现闹钟图标（设置里可关闭）。
            if (isTransition &&
                shouldUseAlarmClockFallback(
                    nowMillis = nowMillis,
                    transitionAtMillis = at,
                    alarmFallbackEnabled = alarmFallback,
                    exactAlarmsAllowed = exactAllowed,
                )
            ) {
                val scheduled =
                    runCatching {
                        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, alarmClockShowIntent()), pendingIntent)
                    }.isSuccess
                if (scheduled) return
            }
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        }

        /** 闹钟图标点开时进入今日页 */
        private fun alarmClockShowIntent(): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_ALARM_SHOW,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun transitionPendingIntent(): PendingIntent = broadcastPendingIntent(REQUEST_CODE)

        private fun beatPendingIntent(): PendingIntent = broadcastPendingIntent(REQUEST_CODE_BEAT)

        private fun broadcastPendingIntent(requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, CapsuleTransitionReceiver::class.java).apply { action = ACTION_TRANSITION },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        companion object {
            const val ACTION_TRANSITION = "com.zhijian.schedule.CAPSULE_TRANSITION"

            /** 与课前提醒闹钟（requestCode=courseId）区分，固定占用一个号段 */
            private const val REQUEST_CODE = 3002

            /** 心跳闹钟：独立槽位，避免与切换闹钟互相覆盖 */
            private const val REQUEST_CODE_BEAT = 3003

            /** 闹钟兜底的展示 Intent，独立槽位避免覆盖卡片跳转 */
            private const val REQUEST_CODE_ALARM_SHOW = 3004
        }
    }
