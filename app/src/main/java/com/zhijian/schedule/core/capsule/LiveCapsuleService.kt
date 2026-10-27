package com.zhijian.schedule.core.capsule

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    /**
     * 实时态（课前倒数/上课中）持有的 partial wake lock：ColorOS 的 osense 会把只有
     * specialUse 前台服务的进程判为 non-perceptible 并冻结。持锁后 CPU 不休眠，
     * 30s 刷新循环与切换闹钟的存活率显著提高；离开实时态立即释放，避免全天耗电。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val wakeLockGuard = Any()

    /** 最近一次构建的骨架通知：再次 startForeground 时复用，避免把内容打回「启动中…」占位 */
    @Volatile
    private var lastRendered: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CapsuleNotifier.ensureChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        carriedNotification(intent)?.let { lastRendered = it }
        val type =
            if (Build.VERSION.SDK_INT >= 34) {
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
            loop =
                scope.launch {
                    while (isActive) {
                        try {
                            // 实时卡片被系统超时撤下（倒计时兜底）后，与上轮 key 相同会一直去重跳过，
                            // 因此发现卡片缺失就允许强制重建重发（含再次请求提升，流体云恢复）。
                            //
                            // 但**用户主动划掉**不能重发：CapsuleNotifier 内部会检查
                            // CapsuleDismissTracker，本场次已记账时不会重新出卡——这里不需要额外判断，
                            // 传 null 的 previousCardKey 只是让 Notifier 有机会重新走一遍决策。
                            val nm = getSystemService(NotificationManager::class.java)
                            val cardPosted =
                                nm
                                    ?.activeNotifications
                                    ?.any { it.id == CapsuleNotifier.LIVE_CARD_ID } == true
                            val posted =
                                coordinator.renderNow(
                                    previousCardKey = lastCardKey.takeIf { cardPosted },
                                    previousSkeletonKey = lastSkeletonKey,
                                ) ?: run {
                                    stopSelf()
                                    break
                                }
                            lastCardKey = posted.cardKey
                            lastSkeletonKey = posted.skeletonKey
                            posted.serviceNotification?.let { lastRendered = it }
                            setLiveState(posted.live)
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
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun setLiveState(live: Boolean) {
        synchronized(wakeLockGuard) {
            if (live) {
                if (wakeLock?.isHeld == true) return
                val manager = getSystemService(PowerManager::class.java) ?: return
                runCatching {
                    wakeLock =
                        manager
                            .newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                WAKE_LOCK_TAG,
                            ).apply {
                                setReferenceCounted(false)
                                // 4 小时上限是防泄漏兜底：正常路径在状态离开实时态时主动释放
                                acquire(WAKE_LOCK_TIMEOUT_MS)
                            }
                }.onFailure { wakeLock = null }
            } else {
                releaseWakeLockLocked()
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockGuard) { releaseWakeLockLocked() }
    }

    private fun releaseWakeLockLocked() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
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

        private const val WAKE_LOCK_TAG = "zhijian:capsule-live"

        /** 实时态 wake lock 的防泄漏上限 */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        fun start(
            context: Context,
            notification: Notification? = null,
        ) {
            val intent = Intent(context, LiveCapsuleService::class.java)
            if (notification != null) intent.putExtra(EXTRA_NOTIFICATION, notification)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCapsuleService::class.java))
        }
    }
}
