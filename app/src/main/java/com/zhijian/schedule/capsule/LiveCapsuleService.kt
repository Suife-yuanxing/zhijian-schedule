package com.zhijian.schedule.capsule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 课程实时胶囊：前台服务每 30s 重算当前/下一节课并刷新状态栏实时通知。
 * 「上课中」与「即将上课（按课前提醒设置倒数）」会请求系统提升为流体云；
 * Android 16+（ColorOS 16）上岛，旧系统为普通常驻进度通知。
 */
@AndroidEntryPoint
class LiveCapsuleService : Service() {

    @Inject
    lateinit var repository: ScheduleRepository

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var holidayDao: HolidayDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastKey: String? = null

    /** 最近一次真正渲染的通知：再次 startForeground 时复用，避免把实时内容打回「启动中…」占位 */
    @Volatile
    private var lastRendered: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CapsuleNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            CapsuleNotifier.NOTIFICATION_ID,
            lastRendered ?: CapsuleNotifier.placeholder(this),
            type,
        )
        if (loop == null) {
            loop = scope.launch {
                while (isActive) {
                    try {
                        val settings = settingsStore.settings.first()
                        if (!settings.capsuleEnabled) {
                            stopSelf()
                            break
                        }
                        val now = LocalDateTime.now()
                        val holidayName = holidayDao.observeAll().first()
                            .firstOrNull { it.date == now.toLocalDate().toString() && it.isOffDay }
                            ?.name
                        val plan = buildCapsulePlan(
                            timetable = repository.activeTimetable.first(),
                            courses = repository.activeCourses.first(),
                            sections = repository.activeSections.first(),
                            now = now,
                            reminderMinutes = if (settings.reminderEnabled) settings.reminderMinutes else 0,
                            holidayName = holidayName,
                        )
                        val posted = CapsuleNotifier.post(this@LiveCapsuleService, plan, lastKey)
                        lastKey = posted.key
                        posted.notification?.let { lastRendered = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // 单次读取/构建失败不终止循环，下一轮重试
                    }
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveCapsuleService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCapsuleService::class.java))
        }
    }
}
