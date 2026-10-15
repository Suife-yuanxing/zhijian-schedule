package com.zhijian.schedule.capsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 胶囊状态切换闹钟（时刻由 CapsuleCoordinator 登记）。
 * 精确闹钟在进程被冻结/杀死时也会唤醒 App，这里立刻重算并渲染新状态，
 * 保证课前提醒窗口开启等切换不依赖后台刷新循环。
 */
@AndroidEntryPoint
class CapsuleTransitionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var coordinator: CapsuleCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val posted = coordinator.renderNow()
                if (posted != null) {
                    // 顺手拉起前台服务让进程内刷新循环继续（精确闹钟触发允许启动；失败不影响本次渲染）
                    runCatching { LiveCapsuleService.start(context, posted.notification) }
                }
            } catch (t: Throwable) {
                // 单次失败不致命：下一次切换闹钟或打开 App 时会重算
            } finally {
                pendingResult.finish()
            }
        }
    }
}
