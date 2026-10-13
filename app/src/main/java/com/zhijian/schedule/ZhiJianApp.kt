package com.zhijian.schedule

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.zhijian.schedule.alarm.Notifications
import com.zhijian.schedule.alarm.ReminderCoordinator
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class ZhiJianApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: ScheduleRepository

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var reminderCoordinator: ReminderCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        appScope.launch {
            repository.ensureInitialized()
            Notifications.ensureChannels(this@ZhiJianApp)
            val settings = settingsStore.settings.first()
            if (settings.autoSyncEnabled && settings.jwUrl.isNotBlank()) {
                syncScheduler.enableDaily()
                syncScheduler.runCatchUpIfNeeded(settings.lastSyncTime)
            }
        }
        // 监听数据变化，自动重排课前提醒
        reminderCoordinator.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
