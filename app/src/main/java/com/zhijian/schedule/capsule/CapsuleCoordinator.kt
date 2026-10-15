package com.zhijian.schedule.capsule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 胶囊状态机：读取数据 → 构建状态 → 渲染通知 → 登记下一次状态切换的精确闹钟。
 *
 * 状态切换（进入课前提醒窗口、上课、下课、跨天）不能只靠服务里的刷新循环：
 * ColorOS 冻结后台进程时循环停摆，胶囊会停在旧状态，课前提醒时刻流体云不会切换。
 * 因此每次渲染后用 AlarmManager 精确闹钟登记下一次切换时刻，进程被冻结/杀死
 * 也由系统按时唤醒重算，让「即将上课」倒计时准时出现。
 */
@Singleton
class CapsuleCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val holidayDao: HolidayDao,
) {

    /** 渲染当前胶囊并登记下一次切换闹钟；胶囊功能关闭时撤销闹钟并返回 null */
    suspend fun renderNow(previousKey: String? = null): CapsuleNotifier.Posted? {
        val settings = settingsStore.settings.first()
        if (!settings.capsuleEnabled) {
            cancelTransition()
            return null
        }
        val now = LocalDateTime.now()
        val plan = buildCapsulePlan(
            timetable = repository.activeTimetable.first(),
            courses = repository.activeCourses.first(),
            sections = repository.activeSections.first(),
            now = now,
            reminderMinutes = if (settings.reminderEnabled) settings.reminderMinutes else 0,
            holidayName = todayHolidayName(now),
        )
        val posted = CapsuleNotifier.post(context, plan, previousKey)
        scheduleNextTransition(plan, now)
        return posted
    }

    /** 胶囊关闭（或不再需要）时撤销待触发的切换闹钟 */
    fun cancelTransition() {
        context.getSystemService(AlarmManager::class.java)?.cancel(transitionPendingIntent())
    }

    private suspend fun todayHolidayName(now: LocalDateTime): String? =
        holidayDao.observeAll().first()
            .firstOrNull { it.date == now.toLocalDate().toString() && it.isOffDay }
            ?.name

    private fun scheduleNextTransition(plan: CapsulePlan, now: LocalDateTime) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = transitionPendingIntent()
        val at = nextTransitionAtMillis(plan, now)
        if (at == null || at <= System.currentTimeMillis()) {
            manager.cancel(pendingIntent)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            // 未授予精确闹钟权限时退化为非精确（与课前提醒同一策略）
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        }
    }

    private fun transitionPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CapsuleTransitionReceiver::class.java).apply { action = ACTION_TRANSITION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_TRANSITION = "com.zhijian.schedule.CAPSULE_TRANSITION"

        /** 与课前提醒闹钟（requestCode=courseId）区分，固定占用一个号段 */
        private const val REQUEST_CODE = 3002
    }
}
