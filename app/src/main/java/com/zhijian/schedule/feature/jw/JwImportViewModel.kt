package com.zhijian.schedule.feature.jw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.JwAdapterRegistry
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

    var url by mutableStateOf("")
        private set
    var loadRequest by mutableStateOf(WebLoadRequest(BLANK_PAGE))
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
                u.contains("login_slogin", true) ||
                u.trimEnd('/').endsWith("/jsxsd") ||
                u.contains("login", true)
        }

    /** 登录后进入主框架 / 课表页（动态引导文案用） */
    val onLoggedInPage: Boolean
        get() = pageUrl.contains("xsMain", true) ||
            pageUrl.contains("xskb", true) ||
            pageUrl.contains("index_initMenu", true)

    /** 停在统一身份认证登录页（sso.jsp 自动跳转而来） */
    val onAuthserverPage: Boolean
        get() = pageUrl.contains("authserver", true)

    /** 地址栏当前识别出的教务类型；未识别为 null */
    val detectedAdapterName: String?
        get() = JwAdapterRegistry.forUrl(url)?.name

    init {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (s.jwUrl.isNotBlank()) {
                val saved = withScheme(s.jwUrl)
                url = saved
                loadRequest = WebLoadRequest(saved, loadRequest.nonce + 1)
            }
            autoSyncEnabled = s.autoSyncEnabled
            lastSyncTime = s.lastSyncTime
        }
    }

    /** 用户可能只输入裸域名；WebView 需要完整 scheme 才能当网址加载 */
    private fun withScheme(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith("http", ignoreCase = true) -> trimmed
            trimmed == BLANK_PAGE -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun onUrlChange(value: String) {
        url = value
    }

    /** 「打开」：总是重新加载（含同 URL 重试） */
    fun reload() {
        val target = withScheme(url)
        if (target.isBlank()) return
        url = target
        pageError = null
        loadRequest = WebLoadRequest(target, loadRequest.nonce + 1)
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
            val trimmed = withScheme(url)
            if (trimmed.isNotBlank()) url = trimmed
            settingsStore.setJw(trimmed, JwAdapterRegistry.forUrl(trimmed)?.name.orEmpty())
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
        /** 未配置时的空白页；不再默认东莞职院，避免新用户被带到错误学校 */
        const val BLANK_PAGE = "about:blank"
    }
}
