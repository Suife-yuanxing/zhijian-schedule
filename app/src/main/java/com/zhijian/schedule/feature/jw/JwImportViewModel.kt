package com.zhijian.schedule.feature.jw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.sync.JwSyncManager
import com.zhijian.schedule.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** WebView 加载请求：nonce 变化即重新加载，即使 URL 与上次相同 */
data class WebLoadRequest(val url: String, val nonce: Int = 0)

@HiltViewModel
class JwImportViewModel @Inject constructor(
    private val syncManager: JwSyncManager,
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    var url by mutableStateOf(DEFAULT_URL)
        private set
    var loadRequest by mutableStateOf(WebLoadRequest(DEFAULT_URL))
        private set
    var loadProgress by mutableIntStateOf(0)
        private set
    var pageUrl by mutableStateOf("")
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var httpErrorHint by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var preview by mutableStateOf<ImportPreview?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var needsLogin by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var autoSyncEnabled by mutableStateOf(false)
        private set
    var lastSyncTime by mutableStateOf(0L)
        private set

    /** WebView 当前停在登录页（动态引导文案用） */
    val onLoginPage: Boolean
        get() {
            val u = pageUrl
            if (u.isBlank()) return false
            return u.contains("LoginToXk", true) ||
                u.trimEnd('/').endsWith("/jsxsd") ||
                u.contains("login", true)
        }

    /** 登录后进入主框架 / 课表页（动态引导文案用） */
    val onLoggedInPage: Boolean
        get() = pageUrl.contains("xsMain", true) || pageUrl.contains("xskb", true)

    init {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (s.jwUrl.isNotBlank()) {
                url = s.jwUrl
                loadRequest = WebLoadRequest(s.jwUrl, loadRequest.nonce + 1)
            }
            autoSyncEnabled = s.autoSyncEnabled
            lastSyncTime = s.lastSyncTime
        }
    }

    fun onUrlChange(value: String) {
        url = value
    }

    /** 「打开」：总是重新加载（含同 URL 重试） */
    fun reload() {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        pageError = null
        loadRequest = WebLoadRequest(trimmed, loadRequest.nonce + 1)
    }

    fun onPageUrl(value: String) {
        pageUrl = value
    }

    fun onLoadProgress(value: Int) {
        loadProgress = value
    }

    fun onLoadError(message: String) {
        pageError = message
    }

    fun onHttpError(code: Int) {
        httpErrorHint = code
    }

    fun dismissHttpError() {
        httpErrorHint = 0
    }

    fun fetchNow() {
        viewModelScope.launch {
            loading = true
            errorMessage = null
            needsLogin = false
            settingsStore.setJw(url.trim(), "")
            when (val result = syncManager.fetchForPreview()) {
                is JwSyncManager.FetchResult.Success -> preview = result.preview
                is JwSyncManager.FetchResult.Failed -> {
                    errorMessage = result.message
                    needsLogin = result.needsLogin
                }
                JwSyncManager.FetchResult.NoConfig -> errorMessage = "请先填写教务地址"
            }
            loading = false
        }
    }

    fun dismissPreview() {
        preview = null
    }

    fun applyPreview() {
        val p = preview ?: return
        viewModelScope.launch {
            repository.applyImport(p, applyConflicts = false)
            preview = null
            done = true
        }
    }

    fun setAutoSync(enabled: Boolean) {
        autoSyncEnabled = enabled
        viewModelScope.launch {
            settingsStore.setAutoSync(enabled)
            if (enabled) scheduler.enableDaily() else scheduler.disableDaily()
        }
    }

    companion object {
        /** 首个适配目标：东莞职业技术学院正方教务（方案 4.6） */
        const val DEFAULT_URL = "https://jw.dgpt.edu.cn/jsxsd/"
    }
}
