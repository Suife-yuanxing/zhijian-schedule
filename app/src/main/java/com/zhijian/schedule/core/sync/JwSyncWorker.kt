package com.zhijian.schedule.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhijian.schedule.core.notifications.Notifications
import com.zhijian.schedule.core.data.prefs.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 每日教务自动同步（方案 4.10）：抓取失败指数退避重试 3 次，仍失败则通知用户。
 */
@HiltWorker
class JwSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: JwSyncManager,
    private val settingsStore: SettingsStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsStore.settings.first()
        if (!settings.autoSyncEnabled || settings.jwUrl.isBlank()) return Result.success()

        return when (val result = syncManager.syncNow(notify = false)) {
            is JwSyncManager.FetchResult.Success -> Result.success()
            is JwSyncManager.FetchResult.Failed -> {
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Notifications.showSyncFailed(
                        applicationContext,
                        result.needsLogin,
                        result.message,
                    )
                    // 未识别/护栏暂停重试也不会好，直接结束；网络类失败保留 WorkManager 的失败态
                    if (result.retryable) Result.failure() else Result.success()
                }
            }
            JwSyncManager.FetchResult.NoConfig -> Result.success()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val UNIQUE_DAILY = "jw_daily_sync"
        const val UNIQUE_CATCHUP = "jw_catchup_sync"
    }
}
