package com.zhijian.schedule.capsule

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 课程实时胶囊：前台服务每 30s 重算当前/下一节课并刷新状态栏实时通知。
 * 「上课中」与「即将上课（按课前提醒设置倒数）」会请求系统提升为流体云；
 * Android 16+（ColorOS 16）上岛，旧系统为普通常驻进度通知。
 *
 * 状态切换本身由 CapsuleCoordinator 的精确闹钟兜底（后台进程被冻结时循环停摆），
 * 本服务的循环只负责进程存活期间的细粒度刷新。
 */
@AndroidEntryPoint
class LiveCapsuleService : Service() {

    @Inject
    lateinit var coordinator: CapsuleCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastCardKey: String? = null
    private var lastSkeletonKey: String? = null

    /** 最近一次构建的骨架通知：再次 startForeground 时复用，避免把内容打回「启动中…」占位 */
    @Volatile
    private var lastRendered: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CapsuleNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        carriedNotification(intent)?.let { lastRendered = it }
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            CapsuleNotifier.SERVICE_NOTIFICATION_ID,
            lastRendered ?: CapsuleNotifier.placeholder(this),
            type,
        )
        if (loop == null) {
            loop = scope.launch {
                while (isActive) {
                    try {
                        // 实时卡片被系统超时撤下（倒计时兜底）/ 被划掉后，与上轮 key 相同会一直
                        // 去重跳过——发现卡片缺失就强制重建重发（含再次请求提升，流体云恢复）
                        val nm = getSystemService(NotificationManager::class.java)
                        val cardPosted = nm?.activeNotifications
                            ?.any { it.id == CapsuleNotifier.LIVE_CARD_ID } == true
                        val posted = coordinator.renderNow(
                            previousCardKey = lastCardKey.takeIf { cardPosted },
                            previousSkeletonKey = lastSkeletonKey,
                        ) ?: run {
                            stopSelf()
                            break
                        }
                        lastCardKey = posted.cardKey
                        lastSkeletonKey = posted.skeletonKey
                        posted.serviceNotification?.let { lastRendered = it }
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

    /** 切换闹钟唤醒时把刚渲染好的通知带进来，避免冷启动瞬间以占位覆盖实时内容 */
    private fun carriedNotification(intent: Intent?): Notification? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_NOTIFICATION)
        }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L

        private const val EXTRA_NOTIFICATION = "capsule_notification"

        fun start(context: Context, notification: Notification? = null) {
            val intent = Intent(context, LiveCapsuleService::class.java)
            if (notification != null) intent.putExtra(EXTRA_NOTIFICATION, notification)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCapsuleService::class.java))
        }
    }
}
