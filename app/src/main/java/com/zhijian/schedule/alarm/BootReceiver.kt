package com.zhijian.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhijian.schedule.capsule.CapsuleCoordinator
import com.zhijian.schedule.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.prefs.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设备重启 / 应用更新后系统会清空所有闹钟，重新登记（方案 4.7）。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var coordinator: ReminderCoordinator

    @Inject
    lateinit var examReminderCoordinator: ExamReminderCoordinator

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var capsuleCoordinator: CapsuleCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                coordinator.refresh()
                examReminderCoordinator.refresh()
                reviveCapsule(context)
            }
        }
    }

    /** 重启/更新后胶囊服务与切换闹钟都被清空，按设置恢复 */
    private fun reviveCapsule(context: Context) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                if (!settingsStore.settings.first().capsuleEnabled) return@launch
                capsuleCoordinator.renderNow()
                runCatching { LiveCapsuleService.start(context) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
