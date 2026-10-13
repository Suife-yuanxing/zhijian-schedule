package com.zhijian.schedule.sync

import android.content.Context
import com.zhijian.schedule.alarm.Notifications
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.JwException
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * 教务抓取 → Diff → 自动应用 → 变更通知（方案 4.10）。
 * 手动导入与自动同步共用同一套适配器，保证解析结果一致。
 */
@Singleton
class JwSyncManager @Inject constructor(
    private val client: OkHttpClient,
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context,
) {

    sealed interface FetchResult {
        data class Success(val preview: ImportPreview) : FetchResult
        data class Failed(val message: String, val needsLogin: Boolean = false) : FetchResult
        data object NoConfig : FetchResult
    }

    private suspend fun fetchPreview(syncMode: Boolean): FetchResult {
        val settings = settingsStore.settings.first()
        val url = settings.jwUrl
        if (url.isBlank()) return FetchResult.NoConfig
        val totalWeeks = repository.activeTimetable.first()?.totalWeeks ?: 20
        return try {
            val adapter = JwAdapterRegistry.forUrl(url)
            val drafts = adapter.fetch(client, url, totalWeeks)
            if (drafts.isEmpty()) {
                FetchResult.Failed("未解析到课程数据，请确认已进入课表页面")
            } else {
                val preview = repository.buildPreview(drafts, ImportSource.JW, syncMode = syncMode)
                settingsStore.setLastSyncTime(System.currentTimeMillis())
                FetchResult.Success(preview)
            }
        } catch (e: JwException.NeedsLogin) {
            FetchResult.Failed(e.message ?: "会话已过期", needsLogin = true)
        } catch (e: JwException) {
            FetchResult.Failed(e.message ?: "抓取失败")
        } catch (e: Exception) {
            FetchResult.Failed(e.message ?: "网络异常")
        }
    }

    /** 手动抓取：返回预览，由用户确认后应用 */
    suspend fun fetchForPreview(): FetchResult = fetchPreview(syncMode = false)

    /** 自动同步：抓取 → 直接应用 → 通知变更 */
    suspend fun syncNow(notify: Boolean = true): FetchResult {
        return when (val result = fetchPreview(syncMode = true)) {
            is FetchResult.Success -> {
                repository.applyImport(result.preview, applyConflicts = false)
                val changes = result.preview.addedCount + result.preview.changedCount + result.preview.removedCount
                if (notify && changes > 0) {
                    Notifications.showSyncChanged(context, result.preview.summary())
                }
                result
            }
            is FetchResult.Failed -> {
                if (notify) Notifications.showSyncFailed(context, result.needsLogin)
                result
            }
            FetchResult.NoConfig -> result
        }
    }
}
