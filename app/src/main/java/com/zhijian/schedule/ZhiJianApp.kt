package com.zhijian.schedule

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.zhijian.schedule.alarm.ExamReminderCoordinator
import com.zhijian.schedule.alarm.Notifications
import com.zhijian.schedule.alarm.ReminderCoordinator
import com.zhijian.schedule.calendar.CalendarSyncCoordinator
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.sync.SyncScheduler
import com.zhijian.schedule.update.ApkDownloader
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

    @Inject
    lateinit var examReminderCoordinator: ExamReminderCoordinator

    @Inject
    lateinit var calendarSyncCoordinator: CalendarSyncCoordinator

    @Inject
    lateinit var apkDownloader: ApkDownloader

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        appScope.launch {
            // 「版本 <= 当前已安装版本」的更新残留（含待安装 APK 与断点）已经没用了
            runCatching { apkDownloader.cleanupOutdated(BuildConfig.VERSION_NAME) }
            // 旧版本的镜像开关默认关闭；升级后一次性打开，保证直连不可用时能自动换源
            runCatching { settingsStore.migrateIfNeeded() }
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
        // 监听考试数据变化，自动重排考前提醒
        examReminderCoordinator.start()
        // 课表数据变化时自动重刷系统日历（「写入系统日历」开关打开时）
        calendarSyncCoordinator.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
