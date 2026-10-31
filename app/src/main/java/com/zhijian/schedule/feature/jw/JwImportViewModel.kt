package com.zhijian.schedule.feature.jw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.AdapterSteps
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.script.JwScriptOutput
import com.zhijian.schedule.core.jw.script.ScriptCatalog
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.net.JwAssetCache
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.sync.JwSyncManager
import com.zhijian.schedule.core.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** WebView 加载请求：nonce 变化即重新加载，即使 URL 与上次相同 */
data class WebLoadRequest(
    val url: String,
    val nonce: Int = 0,
)

@HiltViewModel
class JwImportViewModel
    @Inject
    constructor(
        private val syncManager: JwSyncManager,
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val scheduler: SyncScheduler,
        private val catalog: ScriptCatalog,
        val assetCache: JwAssetCache,
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
        var scriptRunning by mutableStateOf(false)
            private set

        /** 脚本 warnings / 管理操作反馈（accent 色提示条） */
        var scriptNotice by mutableStateOf<String?>(null)
            private set

        /**
         * 预抓取已就绪：WebView 停在可识别教务页时后台提前抓好的课表，
         * 点「抓取」时直接用（零网络等待）。预抓取是只读请求，失败静默。
         */
        var prefetchReady by mutableStateOf(false)
            private set

        private var prefetched: JwSyncManager.FetchResult.Success? = null

        private var prefetchJob: Job? = null

        /**
         * 证书过期放行提示（本校门户专属）。
         *
         * 与 [pageError] 分开：这不是失败，页面照常可用，只是要让用户知道
         * "证书确实过期了，这次是 App 让它过的"。
         */
        var certNotice by mutableStateOf<String?>(null)
            private set

        /** WebView 当前停在登录页（动态引导文案用） */
        val onLoginPage: Boolean
            get() = isJwLoginPage(pageUrl)

        /** 登录后进入主框架 / 课表页（动态引导文案用） */
        val onLoggedInPage: Boolean
            get() = isJwLoggedInPage(pageUrl)

        /** 停在统一身份认证登录页（sso.jsp 自动跳转而来） */
        val onAuthserverPage: Boolean
            get() = isJwAuthserverPage(pageUrl)

        /** 地址栏当前识别出的教务类型；未识别为 null */
        val detectedAdapterName: String?
            get() = JwAdapterRegistry.forUrl(url)?.name

        /** 当前地址对应适配器的网页操作指引（菜单路径 / 注意事项）；未识别为 null */
        val currentSteps: AdapterSteps?
            get() = JwAdapterRegistry.forUrl(withScheme(url))?.steps

        /** 当前地址对应的统一认证入口；学校本地登录入口故障时可改走这里 */
        val ssoEntry: String?
            get() {
                val target = withScheme(url)
                if (target.isBlank()) return null
                return JwAdapterRegistry.forUrl(target)?.ssoEntry(target)
            }

        /** 本次 WebView 加载的是不是统一认证链路（决定 5xx 兜底按钮往哪边引） */
        val loadedSsoEntry: Boolean
            get() = loadRequest.url == ssoEntry

        /** 改跳统一认证登录（与 [openLocalLogin] 互为兜底） */
        fun openSsoLogin() {
            val entry = ssoEntry ?: return
            pageError = null
            httpErrorHint = 0
            loadRequest = WebLoadRequest(entry, loadRequest.nonce + 1)
        }

        /** 回到学校本地登录入口（统一认证链路打不开时的兜底） */
        fun openLocalLogin() {
            val target = withScheme(url)
            if (target.isBlank()) return
            pageError = null
            httpErrorHint = 0
            loadRequest = WebLoadRequest(target, loadRequest.nonce + 1)
        }

        init {
            viewModelScope.launch {
                val s = settingsStore.settings.first()
                if (s.jwUrl.isNotBlank()) {
                    val saved = withScheme(s.jwUrl)
                    url = saved
                    loadRequest = WebLoadRequest(preferredTarget(saved), loadRequest.nonce + 1)
                }
                autoSyncEnabled = s.autoSyncEnabled
                lastSyncTime = s.lastSyncTime
            }
        }

        /** 用户可能只输入裸域名；WebView 需要完整 scheme 才能当网址加载 */
        private fun withScheme(value: String): String = normalizeJwUrl(value, BLANK_PAGE)

        /**
         * WebView 的落点：适配器提供统一认证入口时**优先走统一认证**，
         * 学校本地登录入口常年是重灾区（WAF 拦校外、把浏览器甩到校内 `:8088` 等），
         * 统一认证链路反而稳；没有 SSO 入口的适配器（如 gxgc 门户即统一登录）原样加载。
         */
        private fun preferredTarget(raw: String): String {
            val normalized = withScheme(raw)
            if (normalized.isBlank() || normalized == BLANK_PAGE) return normalized
            return JwAdapterRegistry.forUrl(normalized)?.ssoEntry(normalized) ?: normalized
        }

        fun onUrlChange(value: String) {
            url = value
        }

        /** 「打开」：总是重新加载（含同 URL 重试）；有统一认证入口时优先走它 */
        fun reload() {
            val target = withScheme(url)
            if (target.isBlank()) return
            url = target
            pageError = null
            certNotice = null
            loadRequest = WebLoadRequest(preferredTarget(target), loadRequest.nonce + 1)
        }

        fun onPageUrl(value: String) {
            pageUrl = value
            maybePrefetch()
        }

        /**
         * WebView 停在可识别的教务页时，后台就把课表抓回来（预抓取）。
         *
         * 动机（2026-10 实测）：冷连接首个请求 ~0.9s + 5 个 API ~0.5s，而用户点
         * 「抓取」前必然已在教务页面停留——这段时间足够把数据悄悄抓好，点下去
         * 时直接出预览。抓取是只读请求；失败（含会话未登录）一律静默，
         * 正式报错留给用户手动点抓取的那次。
         */
        private fun maybePrefetch() {
            if (isJwLoginPage(pageUrl)) return
            if (JwAdapterRegistry.forUrl(withScheme(pageUrl)) == null) return
            if (loading || preview != null) return
            if (prefetched != null || prefetchJob?.isActive == true) return
            if (withScheme(url).isBlank()) return
            // 与 fetchNow 同一套入口地址口径：WebView 停在教务页时优先用当前页
            val effective = pageUrl.takeIf { isSameHost(it, withScheme(url)) }
            prefetchJob =
                viewModelScope.launch {
                    when (val result = syncManager.fetchForPreview(effective)) {
                        is JwSyncManager.FetchResult.Success -> {
                            prefetched = result
                            prefetchReady = true
                        }
                        else -> Unit
                    }
                }
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

        fun onCertNotice(message: String) {
            certNotice = message
        }

        fun dismissCertNotice() {
            certNotice = null
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
                // 预抓取在途就等它落地（通常早就好了）；就绪则直接用，零网络等待
                prefetchJob?.takeIf { it.isActive }?.join()
                val ready = prefetched
                if (ready != null) {
                    prefetched = null
                    prefetchReady = false
                    preview = ready.preview
                } else {
                    // WebView 已停在课表页时（URP / 树维 带学期参数），优先用它抓，而不是地址栏
                    val effective = pageUrl.takeIf { isSameHost(it, trimmed) }
                    when (val result = syncManager.fetchForPreview(effective)) {
                        is JwSyncManager.FetchResult.Success -> preview = result.preview
                        is JwSyncManager.FetchResult.Failed -> {
                            errorMessage = result.message
                            needsLogin = result.needsLogin
                        }
                        JwSyncManager.FetchResult.NoConfig -> errorMessage = "请先填写教务地址"
                    }
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
                // 教务抓取的预览就是「换课表」的确认：连同手动编辑过的课程一起覆盖
                repository.applyImport(p, applyConflicts = true)
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

        // ---------- 脚本导入（spec §5：结果进同一 ImportPreview 管线） ----------

        fun dismissScriptNotice() {
            scriptNotice = null
        }

        fun setScriptBusy(running: Boolean) {
            scriptRunning = running
        }

        suspend fun scriptOptions(pageUrl: String): List<ScriptOption> {
            val host = runCatching { java.net.URI(pageUrl).host }.getOrNull()
            return catalog.all(host)
        }

        suspend fun scriptSource(id: String): String? = catalog.readSource(id)

        suspend fun currentTotalWeeks(): Int = repository.activeTimetable.first()?.totalWeeks ?: 20

        fun onScriptEnvelope(envelopeJson: String) {
            viewModelScope.launch {
                val weeks = currentTotalWeeks()
                when (val out = JwScriptOutput.parse(envelopeJson, weeks)) {
                    is JwScriptOutput.ParseResult.Invalid -> errorMessage = out.reason
                    is JwScriptOutput.ParseResult.Ok -> {
                        scriptNotice = out.warnings.takeIf { it.isNotEmpty() }?.joinToString("；")
                        preview =
                            repository.buildPreview(
                                out.drafts,
                                ImportSource.JW,
                                syncMode = false,
                                sectionTimes = out.sectionTimes,
                            )
                    }
                }
            }
        }

        fun onScriptFailed(message: String) {
            errorMessage = message
        }

        companion object {
            /** 未配置时的空白页；不再默认东莞职院，避免新用户被带到错误学校 */
            const val BLANK_PAGE = "about:blank"
        }
    }
