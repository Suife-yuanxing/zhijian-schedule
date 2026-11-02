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
import com.zhijian.schedule.core.jw.credential.JwCredentialMasking
import com.zhijian.schedule.core.jw.credential.JwCredentialOrigin
import com.zhijian.schedule.core.jw.credential.JwCredentialPromptPolicy
import com.zhijian.schedule.core.jw.credential.JwCredentialStore
import com.zhijian.schedule.core.jw.credential.JwCredentialSummary
import com.zhijian.schedule.core.jw.script.JwScriptOutput
import com.zhijian.schedule.core.jw.script.ScriptCatalog
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.net.JwAssetCache
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.sync.JwSyncManager
import com.zhijian.schedule.core.sync.SyncScheduler
import com.zhijian.schedule.core.ui.island.IslandHintTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** WebView 加载请求：nonce 变化即重新加载，即使 URL 与上次相同 */
data class WebLoadRequest(
    val url: String,
    val nonce: Int = 0,
)

/**
 * 页面上抓到、但还没被服务器证实的一对账号密码。
 *
 * [origin] 是提交那一刻页面的 origin：统一认证与教务常在不同域名，
 * 拿它当落库前的同源校验的依据（用户配置的教务地址未必等于最终提交页）。
 */
private data class PendingCredential(
    val origin: String,
    val username: String,
    val password: String,
    val at: Long,
)

/**
 * 「记住这次登录的账号密码？」——气泡要显示的全部内容。
 *
 * 刻意**不含密码字段**：气泡只需要让用户确认「是这个账号吗」，
 * 把明文密码摆到屏幕上没有任何收益，反而多一处会被旁人看到的可能。
 * 密码本身留在 [JwImportViewModel] 的私有字段里，确认后再写入。
 */
data class JwCredentialPrompt(
    val origin: String,
    val maskedUsername: String,
    val siteLabel: String,
    /**
     * 这次问句对应的候选时间戳。
     *
     * 不是装饰：[pendingCredential] 在气泡挂着期间可能被**下一次登录**覆盖
     * （用户不理气泡、直接又登了一次）。确认时若发现候选已经换了，
     * 说明「用户看到的」和「准备存的」不是同一条，此时宁可不存，
     * 也不能拿旧问句去保存新密码。
     */
    val candidateAt: Long,
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
        private val credentialStore: JwCredentialStore,
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
         * 本次登录会自动填充的凭据；null = 没存过、已关闭或密钥失效。
         *
         * 只在内存里以明文存在，随 ViewModel 一起销毁；不参与任何日志、
         * 不写进 SavedState、不经过 Compose 的 @Stable 快照（它是普通 data class，
         * 但内容只被 WebViewBox 读一次就填进页面）。
         */
        var credentialFill by mutableStateOf<JwCredentialFill?>(null)
            private set

        /**
         * 「刚刚把保存的账号填进登录页了」：浮岛限时提示的数据源，null = 不显示。
         *
         * 与 [credentialFill] 的区别是时态——那个是「下次要填什么」，一直躺在内存里；
         * 这个是「刚刚真的填了一次」，五秒后自动消失（见 IslandHint 的进度条）。
         *
         * 为什么不复用 [credentialNotice]：那条提示挂在底部工具条上，说的是**保存的结果**，
         * 由用户点「记住」触发；这条浮在网页上，说的是**回填的发生**，由页面自己触发。
         * 两者可能同时存在（刚保存完又刷新登录页），共用字段会互相抹掉。
         */
        var credentialFillNotice by mutableStateOf<JwCredentialFillNotice?>(null)
            private set

        /**
         * 浮岛提示此刻是否该出现在屏幕上。
         *
         * 与 [credentialFillNotice] 分成两个状态，是为了让退场动画有内容可演：
         * 进度条走完时如果直接把数据清空，胶囊会在 150ms 的淡出里变成一个
         * 只剩图标和进度条的空壳。所以收起只翻这个开关，数据留到下一次填入时才被覆盖。
         *
         * 代价是「已收起」的账号名还留在内存里——而它本来就在 [credentialFill] 里，
         * 且从不上屏、不落盘，不构成新的暴露面。
         */
        var credentialFillNoticeVisible by mutableStateOf(false)
            private set

        /** 已保存凭据的摘要（设置页展示用，不含密码） */
        val credentialSummary: StateFlow<JwCredentialSummary?> = credentialStore.summary

        /**
         * 本次会话抓到的候选账号密码：**提交时暂存，登录成功才落库**。
         *
         * 为什么不在桥回调里直接存：教务登录失败率不低（验证码错、账号锁、WAF 拦校外），
         * 提交即落库会把打错的密码存进去，下次自动填充就是拿错密码去撞锁。
         */
        private var pendingCredential: PendingCredential? = null

        /** 最近一次保存结果，供页面提示（"已记住本次登录的账号" / 失败原因） */
        var credentialNotice by mutableStateOf<String?>(null)
            private set

        /**
         * 待用户确认的「记住这次登录？」候选；null = 没有要问的。
         *
         * 与 [credentialNotice] 是两件事：notice 是**已经发生**的事后提示，
         * 这个是**还没决定**的问句。分开之后，「问」和「说结果」不会互相覆盖——
         * 之前两者共用一个字段，弹窗一关就会把刚写好的结果提示一起抹掉。
         */
        var credentialPrompt by mutableStateOf<JwCredentialPrompt?>(null)
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
                autoFillOn = s.jwAutoFillEnabled
                // 开关是异步读出来的：读完之后要再算一次，否则上面那次
                // reloadCredentialFill 跑在默认值 false 上，永远算不出可回填的凭据
                reloadCredentialFill()
            }
            viewModelScope.launch { credentialStore.refresh() }
        }

        /**
         * 把已保存的凭据读进内存，按当前教务地址决定要不要用。
         *
         * 只保留**明文字段的内存态**：不落盘、不进日志。地址换了学校（origin 变了）
         * 就不再回填——把 A 校的密码灌进 B 校登录页是最糟的一种"方便"。
         */
        fun reloadCredentialFill() {
            viewModelScope.launch {
                if (!autoFillOn) {
                    credentialFill = null
                    return@launch
                }
                val saved = credentialStore.load()
                if (saved == null) {
                    credentialFill = null
                    return@launch
                }
                // 与 onLoginSucceeded 同一条规则：同一注册域即可。
                // 存下来的是**提交页**的 origin（统一认证学校是 authserver），
                // 而用户配置的是教务地址，二者本来就不同 host，严格比会把凭据判成「不该用」。
                // 真正回填时仍然只填 saved.origin 那一页（见 shouldAutoFill），不会串站。
                credentialFill =
                    if (JwCredentialOrigin.isTrustedSubmit(saved.origin, withScheme(url))) {
                        JwCredentialFill(origin = saved.origin, username = saved.username, password = saved.password)
                    } else {
                        null
                    }
            }
        }

        /**
         * 自动填充开关的内存态（初值 false，等设置读出来再同步）。
         *
         * 属性名不带 "AutoFill" 前缀之外的 setter 语义：写操作统一走 [setAutoFillEnabled]，
         * 那里会顺带清空内存里的明文并持久化，避免只改状态不落盘。
         */
        var autoFillOn by mutableStateOf(false)
            private set

        fun setAutoFillEnabled(enabled: Boolean) {
            autoFillOn = enabled
            if (!enabled) credentialFill = null else reloadCredentialFill()
            viewModelScope.launch { settingsStore.setJwAutoFillEnabled(enabled) }
        }

        /**
         * 桥回调：页面提交登录时抓到的一对候选。
         *
         * 这里**只暂存不落库**。字段一律按不可信处理：长度、空白、是否像账号密码都要过一遍，
         * 因为任何被加载的页面（含学校页面上挂的第三方统计脚本）都能调用这条通道。
         */
        fun onCredentialCandidate(json: String) {
            val parsed = parseCandidate(json) ?: return
            // 回调来自 JavaBridge 线程：状态只在主线程写，避免与组合快照打架
            viewModelScope.launch { if (isPlausibleCandidate(parsed)) pendingCredential = parsed }
        }

        private fun parseCandidate(json: String): PendingCredential? =
            runCatching {
                val obj = JSONObject(json)
                PendingCredential(
                    origin = obj.optString("origin").lowercase(),
                    username = obj.optString("username").trim(),
                    password = obj.optString("password"),
                    at = obj.optLong("at"),
                )
            }.getOrNull()

        /**
         * 桥回调：页面自己判定「已经进了教务系统」。
         *
         * 时机仍然只是「服务器已经用这对账号密码换来了会话」，但现在**只提出问句，不写盘**。
         * 真正的写入在用户按下 [confirmCredentialSave] 之后——保存密码是一次
         * 影响下次登录的持久操作，用户有权知道并选择。
         */
        fun onLoginSucceeded() {
            // 回调来自 WebView 的 JavaBridge 线程：先切回主线程再碰任何状态
            viewModelScope.launch {
                // 已经有问句挂着就不再叠加：一次登录可能触发多次跳转判定
                if (credentialPrompt != null) return@launch
                val candidate = pendingCredential ?: return@launch
                // 提交页是否可信：同一注册域即可，不要求严格同 host。
                //
                // 真机实测（东莞职院 2026-11-01）逼出这一条：配置的是 jw.dgpt.edu.cn，
                // 学校 302 到 authserver.dgpt.edu.cn，密码是在后者那页提交的。
                // 原先要求 origin 严格相等，于是统一认证学校的候选被全部丢弃——
                // 功能对这类学校 100% 不工作，而且**没有任何报错**（气泡只是不出现）。
                if (!JwCredentialOrigin.isTrustedSubmit(candidate.origin, withScheme(url))) {
                    pendingCredential = null
                    return@launch
                }
                if (!JwCredentialPromptPolicy.shouldAsk(
                        origin = candidate.origin,
                        username = candidate.username,
                        password = candidate.password,
                        saved = credentialStore.load(),
                    )
                ) {
                    pendingCredential = null
                    return@launch
                }
                credentialPrompt =
                    JwCredentialPrompt(
                        origin = candidate.origin,
                        maskedUsername = JwCredentialMasking.mask(candidate.username),
                        siteLabel = candidate.origin.substringAfter("://"),
                        candidateAt = candidate.at,
                    )
            }
        }

        /**
         * 气泡里点「记住」：此刻才真正写盘。
         *
         * 顺手把自动填充打开——用户既然选择记住，下次就该被填上；
         * 否则「记住了但永远不填」是更奇怪的状态。
         */
        fun confirmCredentialSave() {
            val prompt = credentialPrompt ?: return
            credentialPrompt = null
            viewModelScope.launch {
                val candidate = pendingCredential
                pendingCredential = null
                // 候选在气泡挂着期间被下一次登录覆盖了：用户看到的是旧问句，
                // 存下去的就是另一条。宁可这次不存，也不能错配
                if (candidate == null || candidate.at != prompt.candidateAt) {
                    onCredentialSaveFailed(JwCredentialSaveFailure.CandidateChanged)
                    return@launch
                }
                val ok = credentialStore.save(candidate.origin, candidate.username, candidate.password)
                if (!ok) {
                    // save() 的 false 既可能是加密失败也可能是落盘失败；对用户来说
                    // 两者的可操作面完全相同（再试一次 / 手输），所以归成一类说
                    onCredentialSaveFailed(JwCredentialSaveFailure.Storage)
                    return@launch
                }
                credentialFill =
                    JwCredentialFill(
                        origin = candidate.origin,
                        username = candidate.username,
                        password = candidate.password,
                    )
                autoFillOn = true
                settingsStore.setJwAutoFillEnabled(true)
                credentialNotice = "已记住 " + JwCredentialMasking.mask(candidate.username) + "，下次打开登录页自动填入"
            }
        }

        /**
         * 气泡里点「不用」：丢掉候选，**什么都不记**。
         *
         * 刻意不写「这次别再问我」的隐藏开关：拒绝一次不等于永久拒绝，
         * 下次登录还会问。真不想被问的人去设置里关自动填充，那是一个看得见、能关掉的状态。
         */
        fun dismissCredentialPrompt() {
            credentialPrompt = null
            pendingCredential = null
        }

        fun dismissCredentialNotice() {
            credentialNotice = null
        }

        /**
         * 网页侧确认「账号密码真的被写进登录框了」：弹一条限时浮岛提示。
         *
         * 时间戳在**这里**取，不由网页传：它有双重身份——既是这条提示的身份，
         * 也是进度条的重置令牌（见 [JwCredentialFillNotice.at]）。
         * 取一次系统时钟就够，让页面参与只会引入一个不可信的输入。
         */
        fun onCredentialFilled(username: String) {
            postFillNotice(IslandHintTone.Done, credentialFillHintText(username))
        }

        /**
         * 网页侧报告「这次回填没成」：弹同一条浮岛，换成失败的叉号与危险色。
         *
         * 与成功走同一个入口 [postFillNotice]，所以位置、时长、进度条完全一致——
         * 同一条通道上的两种结局，只差语气。
         */
        fun onCredentialFillFailed(reason: JwCredentialFillFailure) {
            postFillNotice(IslandHintTone.Failed, credentialFillFailedText(reason))
        }

        /**
         * 保存失败：也走浮岛，不再用底部工具条那一行红字。
         *
         * 为什么不复用 [credentialNotice]：那条挂在底部工具条上方，用户点「记住」时
         * 眼睛在网页底部的气泡上——红字出现在半个屏幕之外，而且那里与「抓取失败」
         * 共用同一行，读起来像是导入出了问题。失败属于刚刚那一下，
         * 就该出现在刚刚那一下的位置。
         */
        fun onCredentialSaveFailed(reason: JwCredentialSaveFailure) {
            postFillNotice(IslandHintTone.Failed, credentialSaveFailedText(reason))
        }

        /**
         * 浮岛提示的唯一入口：写入内容、翻可见性、刷新令牌。
         *
         * 令牌取 [System.currentTimeMillis] 而不是由调用方传：它既是这条提示的身份，
         * 也是进度条的重置令牌（见 [JwCredentialFillNotice.at]）。取一次系统时钟就够，
         * 让上层参与只会引入一个不可信的输入。
         *
         * 同一毫秒内连发两条（成功紧接失败）时时间戳相同、令牌不变——这不会漏提示，
         * 内容变了依然会重组；只是进度条不重走，而那一刻本来也不该重走。
         */
        private fun postFillNotice(
            tone: IslandHintTone,
            message: String,
        ) {
            credentialFillNotice =
                JwCredentialFillNotice(tone = tone, message = message, at = System.currentTimeMillis())
            credentialFillNoticeVisible = true
        }

        /** 浮岛提示的进度条走完：收起它（数据留着，退场动画才有内容可演） */
        fun dismissCredentialFillNotice() {
            credentialFillNoticeVisible = false
        }

        /**
         * 隐藏调试入口：手动弹一条指定语气的浮岛提示。
         *
         * 走的是与线上完全相同的 [postFillNotice]，所以位置、时长、进度条、
         * 抖动与震动一个都不少——调试面板里看到的就是用户真会看到的那一条。
         * 若这里另走一条捷径（例如直接改 [credentialFillNotice]），
         * 调试出来的东西就不再代表线上行为。
         *
         * 只由 [JwDebugSheet] 调用；面板本身受 BuildConfig.DEBUG 保护，见 [JwImportScreen]。
         */
        fun showDebugHint(
            tone: IslandHintTone,
            message: String,
        ) {
            postFillNotice(tone, message)
        }

        /** 清空已保存的凭据（高级设置里手动清除，或换学校时） */
        fun clearCredential() {
            pendingCredential = null
            credentialPrompt = null
            credentialFill = null
            // 凭据刚被抹掉，屏幕上却还挂着「已填入保存的教务账号」是自相矛盾的
            credentialFillNoticeVisible = false
            viewModelScope.launch {
                credentialStore.clear()
                // 清除是「做成了一件事」，与回填成功共用同一条成功提示：
                // 它挂在页面顶部，用户刚在设置里点完，回到这里能立刻看到结果
                postFillNotice(IslandHintTone.Done, "已清除保存的教务账号密码")
            }
        }

        /** 候选的可信度门槛：短得离谱、长得离谱、带控制字符的一律丢 */
        private fun isPlausibleCandidate(c: PendingCredential): Boolean {
            val origin = JwCredentialOrigin.of(c.origin) ?: return false
            if (!origin.equals(c.origin, ignoreCase = true)) return false
            if (c.password.isEmpty() || c.password.length > MAX_CREDENTIAL_LENGTH) return false
            if (c.username.length > MAX_CREDENTIAL_LENGTH) return false
            return c.password.none { it.isISOControl() } && c.username.none { it.isISOControl() }
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

        /** 地址栏改了：origin 可能变了，重新决定要不要回填（不立即写盘） */
        fun onUrlCommitted() {
            reloadCredentialFill()
        }

        /** 「打开」：总是重新加载（含同 URL 重试）；有统一认证入口时优先走它 */
        fun reload() {
            val target = withScheme(url)
            if (target.isBlank()) return
            url = target
            pageError = null
            certNotice = null
            loadRequest = WebLoadRequest(preferredTarget(target), loadRequest.nonce + 1)
            // 地址可能刚改过（换学校 / 换校区）：重新比对 origin 再决定回填
            reloadCredentialFill()
            rememberUrl(target)
        }

        /**
         * 把这次「打开」的地址落盘。
         *
         * ## 为什么在这里，而不是只在 [fetchNow] 里
         * 用户真实的动作顺序是「填地址 → 打开 → 登录 → 记住密码」，
         * 而**抓取课表是可以不按的**（只想登进去看看课表，或先存密码下次再说）。
         * 原先只有抓取那一步写盘，于是只登录、不抓取的人会同时踩两个坑：
         * 下次进来地址栏是空的要重打一遍；设置页的「教务账号密码」也永远停在
         * 「先填写教务地址」——明明密码已经存好了。
         *
         * 地址是在按下「打开」那一刻被确认的，就该在那一刻记下来。
         */
        private fun rememberUrl(target: String) {
            viewModelScope.launch {
                if (settingsStore.settings.first().jwUrl == target) return@launch
                settingsStore.setJw(target, JwAdapterRegistry.forUrl(target)?.name.orEmpty())
            }
        }

        fun onPageUrl(value: String) {
            pageUrl = value
            maybePrefetch()
            // 原生侧兜底：有的学校登录页跳转后的 URL 特征在我们的名单里，页面内的
            // loginProbeJs 未必能同时命中（比如整页被 WebVPN 包了一层）。
            // 两条判定是「或」的关系，先到先落库，重复调用会被候选清空自然挡掉
            if (isJwLoggedInPage(value)) onLoginSucceeded()
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
                // 地址为空时绝不能写盘：写下去就是 setJw("", "")，把已保存的教务地址
                // 直接抹掉——下次进来地址栏空着，自动填入也再认不出 origin。
                // 空地址本来就会走下面的 NoConfig 分支报错，不需要顺手清一下配置。
                if (trimmed.isNotBlank()) {
                    url = trimmed
                    settingsStore.setJw(trimmed, JwAdapterRegistry.forUrl(trimmed)?.name.orEmpty())
                }
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
                                // 脚本能声明学期周数与第 1 周周一（本校校历）：两个都带上，
                                // 预览才按同一个长度归一化掩码，确认导入时才会连课表信息一起同步。
                                // 只带总周数不够——开学日期缺了，学期第 10 周照样显示成第 1 周。
                                meta = out.metaOrNull(),
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

            /** 账号密码的长度上限：正常教务凭据远小于此值，超出的按伪造处理 */
            private const val MAX_CREDENTIAL_LENGTH = 200
        }
    }
