package com.zhijian.schedule.core.sync

import android.content.Context
import com.zhijian.schedule.core.notifications.Notifications
import com.zhijian.schedule.core.data.db.GradeDao
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.GradeFetcher
import com.zhijian.schedule.core.jw.JwAdapter
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.JwException
import com.zhijian.schedule.core.jw.QiangZhiAdapter
import com.zhijian.schedule.core.jw.toEntity
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * 教务抓取 → Diff → 自动应用 → 变更通知（方案 4.10）。
 * 手动导入与自动同步共用同一套适配器，保证解析结果一致。
 * 课表抓取成功后顺带刷新成绩缓存（仅强智 jsxsd）；会话过期由适配器侧按能力静默恢复。
 */
@Singleton
class JwSyncManager @Inject constructor(
    private val client: OkHttpClient,
    private val repository: ScheduleRepository,
    private val gradeDao: GradeDao,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context,
) {

    sealed interface FetchResult {
        data class Success(val preview: ImportPreview) : FetchResult
        data class Failed(
            val message: String,
            val needsLogin: Boolean = false,
            /** false = 重试也不会好（未识别系统 / 护栏暂停），交给用户处理 */
            val retryable: Boolean = true,
        ) : FetchResult
        data object NoConfig : FetchResult
    }

    /**
     * @param urlOverride 用户在 WebView 里实际停留的课表页地址（同 host 时优先）。
     *   URP 老版 / 树维的课表页带学期与类型参数，从基路径猜不如直接复用当前页。
     */
    private suspend fun fetchPreview(
        syncMode: Boolean,
        urlOverride: String? = null,
    ): FetchResult {
        val settings = settingsStore.settings.first()
        val url = urlOverride?.takeIf { it.isNotBlank() } ?: settings.jwUrl
        if (url.isBlank()) return FetchResult.NoConfig
        val totalWeeks = repository.activeTimetable.first()?.totalWeeks ?: 20
        return try {
            val adapter = JwAdapterRegistry.forUrl(url)
                ?: return FetchResult.Failed(
                    "暂未识别该教务系统（当前支持：${JwAdapterRegistry.all().joinToString("、") { it.name }}）。" +
                        "可先用 Excel / CSV 导入",
                    retryable = false,
                )
            val drafts = fetchWithRetry(adapter, url, totalWeeks)
            if (drafts.isEmpty()) {
                FetchResult.Failed("未解析到课程数据，请确认已进入课表页面")
            } else {
                if (adapter.id == QiangZhiAdapter.id) syncGradesBestEffort(url)
                val preview = repository.buildPreview(drafts, ImportSource.JW, syncMode = syncMode)
                settingsStore.setLastSyncTime(System.currentTimeMillis())
                Notifications.cancelSyncFailed(context)
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

    /**
     * 学校 WAF / 源站抖动时常返回 5xx 或直接断连，单次失败不代表真的不可用。
     * 会话过期（NeedsLogin）交给 WebView 处理，页面结构错误（ParseFailed）重试也不会好，均不重试。
     */
    private suspend fun fetchWithRetry(
        adapter: JwAdapter,
        url: String,
        totalWeeks: Int,
    ): List<CourseDraft> {
        var last: Exception? = null
        repeat(FETCH_ATTEMPTS) { attempt ->
            try {
                return adapter.fetch(client, url, totalWeeks)
            } catch (e: JwException.NeedsLogin) {
                throw e
            } catch (e: JwException.NetworkFailed) {
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < FETCH_ATTEMPTS - 1) {
                delay(FETCH_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw last ?: JwException.NetworkFailed("网络异常")
    }

    /** 抓课表时顺带抓成绩（同一会话）：失败静默，不影响课表结果 */
    private suspend fun syncGradesBestEffort(url: String) {
        try {
            val records = GradeFetcher.fetch(client, url)
            if (records.isNotEmpty()) {
                gradeDao.replaceAll(records.map { it.toEntity() })
                settingsStore.setGradesFetchedAt(System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 成绩页结构变化/网络抖动不应影响课表同步
        }
    }

    /**
     * 手动抓取：返回预览，由用户确认后应用。
     * [urlOverride] 传当前 WebView 地址，可让 URP / 树维 直接复用已带参数的课表页。
     */
    suspend fun fetchForPreview(urlOverride: String? = null): FetchResult =
        fetchPreview(syncMode = false, urlOverride = urlOverride)

    /** 自动同步：抓取 → 护栏检查 → 直接应用 → 通知变更 */
    suspend fun syncNow(notify: Boolean = true): FetchResult =
        when (val result = fetchPreview(syncMode = true)) {
            is FetchResult.Success -> {
                val warning = result.preview.warning
                if (warning != null) {
                    if (notify) Notifications.showSyncFailed(context, needsLogin = false, reason = warning)
                    FetchResult.Failed(warning, retryable = false)
                } else {
                    repository.applyImport(result.preview, applyConflicts = false)
                    val changes = result.preview.addedCount + result.preview.changedCount + result.preview.removedCount
                    if (notify && changes > 0) {
                        Notifications.showSyncChanged(context, result.preview.summary())
                    }
                    result
                }
            }
            is FetchResult.Failed -> {
                if (notify) Notifications.showSyncFailed(context, result.needsLogin, result.message)
                result
            }
            FetchResult.NoConfig -> result
        }

    private companion object {
        /** 首次 + 一次退避重试，覆盖学校 WAF 的短时抖动；后台同步还有 WorkManager 层重试 */
        const val FETCH_ATTEMPTS = 2
        const val FETCH_RETRY_DELAY_MS = 800L
    }
}
