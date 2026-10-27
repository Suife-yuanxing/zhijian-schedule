package com.zhijian.schedule.core.capsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 胶囊状态切换闹钟（时刻由 CapsuleCoordinator 登记）。
 * 精确闹钟在进程被冻结/杀死时也会唤醒 App，这里立刻重算并渲染新状态，
 * 保证课前提醒窗口开启等切换不依赖后台刷新循环。
 *
 * 同时承载**用户划掉实时卡片**的回调：
 * 官方明确要求不得重新发布用户已关闭的动态，所以这条分支只记账、不重发。
 */
@AndroidEntryPoint
class CapsuleTransitionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var coordinator: CapsuleCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // 用户主动清除实时卡片：记账后直接返回。
        // 不在这里重发——重发就等于跟用户对着干，可能被撤销通知权限。
        if (intent.action == ACTION_CARD_DISMISSED) {
            val sessionKey = intent.getStringExtra(EXTRA_DISMISSED_SESSION)
            if (sessionKey != null) {
                CapsuleDismissTracker.markDismissed(context, sessionKey)
            }
            // 实时卡片没了，但骨架通知仍在，服务不需要停
            return
        }
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val posted = coordinator.renderNow()
                if (posted != null) {
                    // 顺手拉起前台服务让进程内刷新循环继续（精确闹钟触发允许启动；失败不影响本次渲染）
                    runCatching { LiveCapsuleService.start(context, posted.serviceNotification) }
                }
            } catch (t: Throwable) {
                // 单次失败不致命：下一次切换闹钟或打开 App 时会重算
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** 用户划掉实时卡片（由 CapsuleNotifier 的 deleteIntent 投递） */
        const val ACTION_CARD_DISMISSED = "com.zhijian.schedule.CAPSULE_CARD_DISMISSED"

        /** 被划掉的场次标识，用于记账 */
        const val EXTRA_DISMISSED_SESSION = "capsule_dismissed_session"
    }
}
