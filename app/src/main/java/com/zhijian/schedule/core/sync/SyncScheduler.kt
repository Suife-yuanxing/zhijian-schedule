package com.zhijian.schedule.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 每日一次（方案 4.10：默认每日 1 次，建议凌晨执行） */
    fun enableDaily() {
        val request = PeriodicWorkRequestBuilder<JwSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInitialDelay(Duration.ofHours(3))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            JwSyncWorker.UNIQUE_DAILY,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disableDaily() {
        WorkManager.getInstance(context).cancelUniqueWork(JwSyncWorker.UNIQUE_DAILY)
    }

    /** 打开 App 时距上次成功抓取 > 6h 的兜底刷新 */
    fun runCatchUpIfNeeded(lastSyncTime: Long) {
        val sixHours = 6 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastSyncTime < sixHours) return
        val request = OneTimeWorkRequestBuilder<JwSyncWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            JwSyncWorker.UNIQUE_CATCHUP,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
