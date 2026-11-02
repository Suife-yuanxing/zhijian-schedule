package com.zhijian.schedule.feature.jw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.credential.JwCredentialOrigin
import com.zhijian.schedule.core.jw.credential.JwCredentialScripts
import com.zhijian.schedule.core.jw.script.JwScriptRunner
import com.zhijian.schedule.core.net.ExpiredCertTolerance
import com.zhijian.schedule.core.net.JwAssetCache
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * 教务导入页的 WebView 宿主：唯一一处直接配置 WebView 的地方
 * （脚本桥、Cookie、缩放、多窗口、SSO 恢复、WAF 错误页检测都在这里）。
 *
 * 注入的四段 JS 都只做「让学校页面能在手机 WebView 里正常工作」这一件事，
 * 每段的来历写在各自的常量上。
 */

private const val WEB_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/**
 * 部分国产系统（如 ColorOS 强制应用密度）下 WebView 的 device-width 会被算成超大值
 * （实测 666 CSS px，屏幕仅 360），教务页面因此比屏幕宽、被横向裁切。
 * 页面加载完成后按 screen.width / 实际布局宽度 设置 initial-scale，把整页缩放到屏幕内；
 * 页面本身正常时（布局宽度 ≈ 屏幕宽度）不做任何处理。
 */
private const val FIT_VIEWPORT_JS = """
(function () {
  try {
    if (window.__zjViewportFit) return;
    var sw = (window.screen && screen.width) || 0;
    var w = Math.max(window.innerWidth || 0, (document.documentElement && document.documentElement.scrollWidth) || 0);
    if (!sw || !w || w <= sw + 1) return;
    window.__zjViewportFit = true;
    var s = (sw / w).toFixed(4);
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; }
    var c = (m.getAttribute('content') || 'width=device-width').trim();
    c = /initial-scales*=/.test(c) ? c.replace(/initial-scales*=s*[0-9.]+/g, 'initial-scale=' + s) : c + ', initial-scale=' + s;
    if (!/user-scalable/i.test(c)) c += ', user-scalable=yes';
    if (!/maximum-scale/i.test(c)) c += ', maximum-scale=5';
    m.setAttribute('content', c);
    if (!m.parentNode) (document.head || document.documentElement).appendChild(m);
  } catch (e) {}
})()
"""

/**
 * 强智本地登录表单（Ldap 密码与统一认证不同，本地登录走不通）和裸 JSON 错误页
 * （如会话过期后的 {"flag1":3,"msgContent":…}）都是死路，检测到就改跳适配器给出的 SSO 入口。
 * 入口地址按当前 URL 动态推导，不再写死某所学校。
 */
private fun ssoRecoverJs(entryUrl: String?): String {
    if (entryUrl.isNullOrBlank()) return ""
    val host = runCatching { URI(entryUrl).host }.getOrNull() ?: return ""
    if (host.isBlank()) return ""
    val entry = JSONObject.quote(entryUrl)
    val hostJs = JSONObject.quote(host.lowercase())
    return """
        (function () {
          try {
            var host = (location.hostname || '').toLowerCase();
            if (host !== $hostJs) return;
            if (location.href.indexOf('/sso.jsp') !== -1) return;
            var form = document.querySelector('form[action*="LoginToXkLdap"]');
            var ct = (document.contentType || '').toLowerCase();
            var text = ((document.body && document.body.innerText) || '').trim();
            var json = ct.indexOf('json') !== -1 || (text.charAt(0) === '{' && text.indexOf('msgContent') !== -1);
            if (form || json) {
              var last = Number(sessionStorage.getItem('zj_sso_at') || 0);
              if (Date.now() - last < 10000) return;
              sessionStorage.setItem('zj_sso_at', String(Date.now()));
              location.href = $entry;
            }
          } catch (e) {}
        })()
        """.trimIndent()
}

/**
 * 学校 xsMain.jsp 外壳页自身的 bug：初始「个人中心」iframe（#Frame0）用 CSS height:86% 撑高，
 * 但其父容器高度恒为 0（学校页面里给 Frame0 设高的 JS 被注释掉、easyui 的 JS 也没引入），
 * 主内容区因此一直是空白。这里按学校自家菜单脚本（showMenuErji）的同款公式补高度。
 */
private val JW_SHELL_FIX_JS = """
(function () {
  try {
    if ((location.hostname || '').slice(-11) !== 'dgpt.edu.cn') return;
    var fix = function () {
      var f = document.getElementById('Frame0');
      if (!f) return;
      var vh = window.innerHeight || 0;
      if (vh < 600) return;
      var tabs = document.querySelector('.tabs-wrap') || document.querySelector('.tabs');
      var north = document.getElementById('mainNorthPanle');
      var h = vh - (tabs ? tabs.offsetHeight : 26) - (north ? north.offsetHeight : 60) - 5;
      if (h > 200 && Math.abs(f.offsetHeight - h) > 50) f.style.height = h + 'px';
    };
    fix();
    setTimeout(fix, 1000);
    setTimeout(fix, 2500);
  } catch (e) {}
})()
"""

/**
 * 学校 WAF 的 5xx 错误页常常以 200 返回，WebView 的 onReceivedHttpError 收不到；
 * 页面加载完成后补查一次正文，命中就返回错误码，交给自动重试 / 顶部提示条。
 */
private const val WAF_ERROR_CHECK_JS = """
(function () {
  try {
    var s = ((document.title || '') + ' ' + (document.body ? (document.body.innerText || '') : '')).slice(0, 4000);
    if (/50[234]s*(Bad Gateway|Gateway Time-?out|Service Unavailable)/i.test(s) || s.indexOf('云防护节点') >= 0) {
      if (/504/.test(s)) return '504';
      if (/503/.test(s)) return '503';
      return '502';
    }
    return '';
  } catch (e) { return ''; }
})()
"""

/**
 * 修「视口单位塌成 0」：页面白屏而 DOM 完好的真凶。
 *
 * ## 现象
 * 广西工程门户登录页（html/body 都是 height:100%，登录卡片垂直居中）在 WebView 里整页空白：
 * 正文 innerText 有内容、节点存在、样式表全部加载成功，但
 * documentElement.getBoundingClientRect().height === 0、100vh 实测 0px——
 * 初始包含块高度是 0，百分比高度沿 html → body → 卡片全链路解析成 0，内容被压成一条 0 高的线。
 *
 * ## 为什么是 0
 * WebView 在还没量到尺寸（宽高为 0）时就提交了第一次导航，Chromium 把视口高度按 0 锁住，
 * 之后再怎么重排都不再更新。对照实验：同一台机器上百度首页的 100vh 同样是 0px，
 * 说明与学校页面无关，是本工程 WebView 的加载时序问题（[loadWhenSized] 从源头修，
 * 本段只做兜底，防止其它时序再次把它锁死）。
 *
 * ## 什么时候动手
 * 只在「视口其实有高度、但 100vh 量出来接近 0」时才给 html 一个像素高度；
 * 内容更高的页面照常滚动（scrollHeight 由内容撑开）。量出来正常就一个字节都不改，
 * 所以对本来排版正确的教务页是空操作。
 */
internal const val VIEWPORT_HEIGHT_REPAIR_JS = """
(function () {
  try {
    var h = (window.visualViewport && visualViewport.height) || window.innerHeight || 0;
    if (!h || h < 1) return;
    var root = document.documentElement;
    if (!root) return;
    var probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;top:-9999px;left:0;width:0;height:100vh';
    (document.body || root).appendChild(probe);
    var vh = probe.getBoundingClientRect().height;
    probe.parentNode && probe.parentNode.removeChild(probe);
    if (vh > 1) return;
    root.style.setProperty('height', h + 'px', 'important');
    root.style.setProperty('min-height', h + 'px', 'important');
    if (document.body) document.body.style.setProperty('min-height', h + 'px', 'important');
  } catch (e) {}
})()
"""

/**
 * 这个 SSL 错误是不是"证书日期不对"。
 *
 * Chromium 网络栈报 `SSL_DATE_INVALID`，老实现报 `SSL_EXPIRED`，两者同义；
 * 其余错误码（主机名不匹配、自签名、签发者未知）一律不认——它们与过期不是一回事。
 */
private fun isCertDateError(error: SslError?): Boolean =
    error != null &&
        (error.primaryError == SslError.SSL_DATE_INVALID || error.primaryError == SslError.SSL_EXPIRED)

private const val PENDING_LOAD_TAG: Int = 0x7A6A6C31

/**
 * 现在能不能提交导航：宽高都量到了才算。
 *
 * 抽成纯函数是为了能进本机 JVM 单测——判定本身只有一行，但它是白屏的开关：
 * 尺寸为 0 就导航会让 Chromium 把视口高度锁成 0，页面永远塌成 0 高。
 */
internal fun shouldLoadWhenSized(
    width: Int,
    height: Int,
): Boolean = width > 0 && height > 0

/**
 * 量到尺寸之后再提交导航——白屏的真正成因就在这里。
 *
 * AndroidView 的 factory / update 都在**布局之前**执行：那一刻 WebView 的宽高还是 0，
 * 直接 loadUrl 会让 Chromium 把视口高度按 0 锁死，此后任何重排都不再更新它。
 * 依赖 100vh / 百分比高度的页面（实测：广西工程门户与百度首页都一样）因此整页塌成 0 高，
 * 只剩一片空白。
 *
 * 尺寸已经是正数时立刻加载，不多等一帧；否则挂一个布局监听，等第一次量到尺寸再加载。
 * 同一个 WebView 上只保留**最后一个**待加载请求：地址栏连点两次时，旧监听器必须摘掉，
 * 否则两个 URL 会依次导航，用户看到的是先加载旧地址又跳新地址。
 */
private fun loadWhenSized(
    webView: WebView,
    url: String,
) {
    (webView.getTag(PENDING_LOAD_TAG) as? View.OnLayoutChangeListener)?.let {
        webView.removeOnLayoutChangeListener(it)
        webView.setTag(PENDING_LOAD_TAG, null)
    }
    if (shouldLoadWhenSized(webView.width, webView.height)) {
        webView.loadUrl(url)
        return
    }
    val listener =
        object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (shouldLoadWhenSized(view.width, view.height)) {
                    view.removeOnLayoutChangeListener(this)
                    view.setTag(PENDING_LOAD_TAG, null)
                    webView.loadUrl(url)
                }
            }
        }
    webView.setTag(PENDING_LOAD_TAG, listener)
    webView.addOnLayoutChangeListener(listener)
}

/**
 * 账号密码相关的注入开关。
 *
 * 与上面几段「修页面」的脚本不同，这两段碰的是用户输入的明文，所以判定要收得更紧：
 * 只有「页面 origin 与保存凭据时的 origin 完全一致」才回填，
 * 只有「页面上确实存在可见的密码输入框」才装捕获监听——不在无关页面上摊开敏感逻辑。
 */
private const val CREDENTIAL_PROBE_JS = """
(function () {
  try {
    var list = document.querySelectorAll('input[type=password]');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.disabled || el.readOnly) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) continue;
      var s = window.getComputedStyle(el);
      if (s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) <= 0.1) continue;
      return 'yes';
    }
    return '';
  } catch (e) { return ''; }
})()
"""

/**
 * 这一次页面加载要不要把密码灌进输入框。
 *
 * 抽成纯函数是为了能进本机单测：它是「会不会把密码送到别的站点」的唯一开关。
 * 三种情况一律不回填——没存过、地址还没有 scheme（未规范化）、origin 对不上。
 */
internal fun shouldAutoFill(
    pageUrl: String,
    fill: JwCredentialFill?,
): Boolean {
    if (fill == null) return false
    if (fill.password.isEmpty()) return false
    val origin = JwCredentialOrigin.of(pageUrl) ?: return false
    return origin.equals(fill.origin, ignoreCase = true)
}

/** WAF 5xx 多为瞬时抖动：自动重试两次再交给用户，避免一进来就是错误页 */
private const val WAF_MAX_RETRIES = 2
private const val WAF_RETRY_DELAY_MS = 1500L

/**
 * 页面加载完成时挂上凭据相关的两段逻辑：捕获 / 登录判定 → 回填。
 *
 * ## 为什么捕获与登录判定必须**无条件**注入
 * 这两段脚本是「第一次凭据从哪来」的唯一来源，而回填的前提是**已经存过**凭据。
 * 曾经把它们写在 `fill != null` 的判断之后（97c68c9 引入），于是形成自举死锁：
 * 没存过 → fill 为 null → 早返回 → 捕获没装 → 候选永远为空 → 气泡不出现 → 存不下。
 * 新装用户 100% 走不出第一步，且不报错、不崩溃、界面毫无反应。
 * 所以顺序反过来：**先装捕获，再谈回填**，两者之间没有任何前置依赖。
 *
 * ## 为什么回填仍然要过两道闸
 * 1. [shouldAutoFill]：页面 origin 与凭据 origin 严格一致，防止把 A 校密码灌进 B 校页面；
 * 2. `filledOrigin`：同一 origin 一次加载内只填一次，避免 onPageFinished 多次触发时
 *    覆盖用户手改的输入、并让浮岛提示重复走一遍进度条。
 *
 * 三段都是 fire-and-forget，任何一段失败都不影响页面本身：
 * 凭据是附加能力，不能拖累导入。
 */
private fun applyCredentialScripts(
    view: WebView,
    url: String?,
    fill: JwCredentialFill?,
    filledOrigin: Array<String?>,
    onFilled: (String) -> Unit,
    onFillFailed: (JwCredentialFillFailure) -> Unit,
) {
    if (url.isNullOrBlank()) return
    val origin = JwCredentialOrigin.of(url) ?: return
    // 捕获 + 登录成功判定（页面内自带 `__zjCaptureInstalled` 幂等标记）。
    // 与「有没有存过凭据」完全无关：任何一页的登录都值得记下来，第一次尤其如此。
    view.evaluateJavascript(JwCredentialScripts.captureJs, null)
    view.evaluateJavascript(JwCredentialScripts.loginProbeJs, null)
    // 没有可回填的凭据就不该报任何失败：新用户第一次打开登录页时 fill 恒为 null，
    // 那不是「填失败了」，那是「还没存过东西」——把它做成红叉等于每次开页都骂一句
    val pending = fill ?: return
    if (!shouldAutoFill(url, pending)) return
    if (filledOrigin[0] == origin) return
    // 先探测页面上是否真有可见的密码框：没有就别把密码送进去，
    // 也别记「这个 origin 已经填过」——下一帧页面可能才长出登录框
    probeAndFill(view, url, origin, pending, filledOrigin, onFilled, onFillFailed)
}

/** 探测不到密码框时，隔多久再看一眼；到这个次数还没有，才判定为 [JwCredentialFillFailure.NoInput] */
private const val PROBE_RETRY_DELAY_MS = 700L

/** 最多看几次。首次 + 两次重试约 1.4 秒，覆盖「登录表单由 JS 渲染」的常见延迟 */
private const val PROBE_MAX_ATTEMPTS = 3

/**
 * 探测密码框并回填；找不到时**先等一会儿再看**，看够了才判定失败。
 *
 * ## 为什么不能一次探测不到就报失败
 * `onPageFinished` 的触发时刻是「主文档加载完」，不是「页面渲染完」。统一认证与多数
 * 教务的登录表单是**之后**由 JS 拼出来的：真机上实测 300~900ms 才出现。
 * 一次探不到就弹红叉加震动，用户会在看到「没能填入」之后不到一秒，
 * 又眼睁睁看着账密被填进去——这比不提示更让人不信任这个功能。
 *
 * ## 为什么重试有上限
 * 有的页面（例如已登录的课表页套着旧 frame）永远长不出密码框。无限重试会让
 * 「这次没填上」这件事永远不被告知，而用户确实需要知道。三次、约 1.4 秒，
 * 是「表单由 JS 渲染」与「这页真没有」之间的经验分界。
 *
 * ## 为什么是递归而不是循环
 * 每次探测都是一次异步的 `evaluateJavascript` 回调。链式写法让「等上一次结果再决定
 * 要不要再来一遍」在代码结构上就是串行的；写成循环反而要靠额外状态变量手动排序。
 */
private fun probeAndFill(
    view: WebView,
    url: String,
    origin: String,
    pending: JwCredentialFill,
    filledOrigin: Array<String?>,
    onFilled: (String) -> Unit,
    onFillFailed: (JwCredentialFillFailure) -> Unit,
    attempt: Int = 1,
) {
    view.evaluateJavascript(CREDENTIAL_PROBE_JS) { raw ->
        if (raw?.trim('"') != "yes") {
            // 期间已经被别的回调填过了：这不是失败，是重复触发
            if (filledOrigin[0] == origin) return@evaluateJavascript
            if (attempt >= PROBE_MAX_ATTEMPTS) {
                // 停在**登录页**却始终摸不到密码框，才是真的异常：页面结构变了、或它把框藏了起来。
                // 停在别的页面（课表页、门户首页）本来就没有框，那不是失败
                if (isJwLoginPage(url)) onFillFailed(JwCredentialFillFailure.NoInput)
                return@evaluateJavascript
            }
            view.postDelayed(
                { probeAndFill(view, url, origin, pending, filledOrigin, onFilled, onFillFailed, attempt + 1) },
                PROBE_RETRY_DELAY_MS,
            )
            return@evaluateJavascript
        }
        // 探测是异步的：两次 onPageFinished 挨得很近时会各自发起一次探测，
        // 命中后又各自回填一次。填两次值一样，但浮岛提示会被重置、进度条重新走，
        // 读作「它怎么又弹了一次」。回填前再确认一次，把这件事变成幂等的
        if (filledOrigin[0] == origin) return@evaluateJavascript
        filledOrigin[0] = origin
        // 回填结果是脚本的返回值：'user+pass' / 'pass' / ''（脚本一项都没写进去）。
        // 只有非空才算「真的填进去了」——这是成功提示唯一的触发源。
        //
        // 为什么不拿「页面有密码框」当依据：探测与回填之间页面可能已经重排，
        // 更常见的是脚本被页面自己的「安全提示」分支挡住（见 fillJs 首行）。
        // 报喜要报发生在输入框里的事，不是发生在浏览器里的事。
        view.evaluateJavascript(
            JwCredentialScripts.fillJs(origin, pending.username, pending.password),
        ) { result ->
            if (!result.isNullOrBlank() && result.trim('"').isNotEmpty()) {
                onFilled(pending.username)
            } else {
                // 框探测到了、值却没写进去：setValue 抛异常、或 element 在两次调用之间被移除
                onFillFailed(JwCredentialFillFailure.WriteRejected)
            }
        }
    }
}

/**
 * WebView 宿主：request.nonce 一变就重新加载（同 URL 重试也算新请求）；
 * 页面侧事件通过回调交回 ViewModel / 页面，这里不持有任何业务状态。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewBox(
    request: WebLoadRequest,
    onPageUrl: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit,
    onHttpError: (Int) -> Unit,
    onCertNotice: (String) -> Unit = {},
    onWebView: (WebView?) -> Unit = {},
    assetCache: JwAssetCache? = null,
    /** 保存过的教务凭据；null = 没存过或用户关掉了自动填充 */
    credentialFill: JwCredentialFill? = null,
    /** 页面在提交登录时抓到的一对候选账号密码（JSON 字符串，原生侧再解析校验并暂存） */
    onCredentialCandidate: (String) -> Unit = {},
    /** 页面自己判定「已经进入教务系统」：此刻暂存的候选才被服务器验证过，可以落库 */
    onLoginSucceeded: () -> Unit = {},
    /** 账密**真的被写进了登录框**：参数是填进去的那个账号，供页面弹一条限时浮岛提示 */
    onCredentialFilled: (String) -> Unit = {},
    /** 本该填上却没填上：与 [onCredentialFilled] 共用同一套浮岛提示，只换语气 */
    onCredentialFillFailed: (JwCredentialFillFailure) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lastLoaded = remember { mutableStateOf<WebLoadRequest?>(null) }
    // WAF 5xx 自动重试计数；每次新的加载请求归零
    val wafRetries = remember { IntArray(1) }
    // 记录「上一次已经回填过哪个 origin」：教务页常见 onPageFinished 触发多次，
    // 每次都灌一遍密码会覆盖用户手改的输入；但同一次加载里也可能先跳统一认证再回教务，
    // 只按 nonce 记一个布尔会漏掉第二次进入登录页，所以按 origin 记账
    val filledBox = remember(request.nonce) { arrayOfNulls<String>(1) }
    // 同上：nonce 一变 remember 就换新数组，而 webViewClient 只创建一次，
    // 必须经 rememberUpdatedState 才能让「本页已回填」的标记跟着重置
    val filledOrigin = rememberUpdatedState(filledBox)
    // 桥只注入一次（WebView 创建时），但回调每轮组合都可能换实例：
    // 用 rememberUpdatedState 持有「最新那一个」，否则桥永远调用首帧的旧闭包
    val candidateHandler = rememberUpdatedState(onCredentialCandidate)
    val loginHandler = rememberUpdatedState(onLoginSucceeded)
    // 同一个理由：evaluateJavascript 的回调跑在 WebViewClient 实例里，只能经它拿到最新闭包
    val filledHandler = rememberUpdatedState(onCredentialFilled)
    val fillFailedHandler = rememberUpdatedState(onCredentialFillFailed)
    // 同一个坑：凭据是 init 里异步从磁盘读出来的，首帧一定是 null。
    // factory 与 webViewClient 只创建一次，直接闭包捕获会永远读到那个 null
    val fillState = rememberUpdatedState(credentialFill)
    val ssoEntry =
        remember(request.url) {
            runCatching { JwAdapterRegistry.forUrl(request.url)?.ssoEntry(request.url) }.getOrNull()
        }
    val ssoJs = remember(ssoEntry) { ssoRecoverJs(ssoEntry) }
    // ssoJs 同理：它随 request.url 变化，而 webViewClient 是首次组合时的那个实例
    val ssoState = rememberUpdatedState(ssoJs)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context)
                .apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 不再设 setDatabaseEnabled：WebSQL 数据库 API 已被 Android 废弃、引擎侧也已移除
                    // （Chrome 119 起），现行做法是用上面的 DOM storage 承载页面本地存储
                    settings.userAgentString = WEB_UA
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // 支持页面 viewport meta，按手机宽度布局；否则老教务页会以桌面宽度渲染而被横向裁切
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // 办事大厅 / WebVPN 门户的卡片用 window.open 打开新页，
                    // 不开启多窗口支持会导致点击后毫无反应（弹窗被静默丢弃）
                    settings.setSupportMultipleWindows(true)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    // 脚本执行桥：创建时永久注入（运行时注入对已加载页面不保证生效）
                    addJavascriptInterface(JwScriptRunner.PaperJavascriptBridge(), "PaperBridge")
                    // 凭据桥：与脚本桥分开注入（见 JwCredentialBridge 的 KDoc）。
                    // 回调来自 JavaBridge 线程，这里只转发，线程切换由 ViewModel 负责
                    addJavascriptInterface(
                        JwCredentialBridge(
                            candidateSink = { json -> candidateHandler.value(json) },
                            loggedInSink = { loginHandler.value() },
                        ),
                        JwCredentialBridge.NAME,
                    )
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView?,
                                newProgress: Int,
                            ) {
                                onProgress(newProgress)
                            }

                            // 把弹窗页面直接载入当前 WebView（应用内单窗口方案）
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            /**
                             * 门户静态资源缓存：学校资源不带任何缓存头（见 [JwAssetCache]），
                             * 命中直接回，未命中放行并后台存一份。主框架与 POST 一律不碰，
                             * 任何异常都退回不拦截——页面可用性永远优先。
                             */
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                req: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val cache = assetCache ?: return null
                                val url = req?.url?.toString() ?: return null
                                if (req.method != "GET") return null
                                val hit = runCatching { cache.intercept(url) }.getOrNull() ?: return null
                                return WebResourceResponse(
                                    hit.contentType,
                                    hit.charset,
                                    ByteArrayInputStream(hit.bytes),
                                )
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                url?.takeIf { it.isNotBlank() }?.let(onPageUrl)
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                url?.takeIf { it.isNotBlank() }?.let(onPageUrl)
                                // 先补视口高度：白屏页面在这里被救回来，后面的脚本才有可用的版面
                                view?.evaluateJavascript(VIEWPORT_HEIGHT_REPAIR_JS, null)
                                view?.evaluateJavascript(FIT_VIEWPORT_JS, null)
                                ssoState.value.takeIf { it.isNotEmpty() }?.let { js ->
                                    view?.evaluateJavascript(js, null)
                                }
                                view?.evaluateJavascript(JW_SHELL_FIX_JS, null)
                                view?.let { wv ->
                                    applyCredentialScripts(
                                        view = wv,
                                        url = url,
                                        fill = fillState.value,
                                        filledOrigin = filledOrigin.value,
                                        onFilled = { username -> filledHandler.value(username) },
                                        onFillFailed = { reason -> fillFailedHandler.value(reason) },
                                    )
                                }
                                // WAF 错误页可能以 200 返回：先查正文；GET 页自动重试，登录 POST 直接提示
                                view?.evaluateJavascript(WAF_ERROR_CHECK_JS) { result ->
                                    val code = result?.trim('"')?.toIntOrNull() ?: 0
                                    if (code == 0) {
                                        wafRetries[0] = 0
                                        return@evaluateJavascript
                                    }
                                    val isLoginPost = url?.contains("LoginToXk", ignoreCase = true) == true
                                    if (!isLoginPost && wafRetries[0] < WAF_MAX_RETRIES) {
                                        wafRetries[0]++
                                        view?.postDelayed({ view.reload() }, WAF_RETRY_DELAY_MS)
                                    } else {
                                        onHttpError(code)
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                req: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (req?.isForMainFrame == true) {
                                    onError(error?.description?.toString() ?: "网络错误")
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                req: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (req?.isForMainFrame == true) {
                                    val code = errorResponse?.statusCode ?: 0
                                    // 5xx 交给正文检测：可能以 200 返回，且要先自动重试再提示
                                    if (code in 400..499) onHttpError(code)
                                }
                            }

                            /**
                             * 校方证书过期（`SSL_DATE_INVALID`）时按 [ExpiredCertTolerance] 放行，
                             * 其余 SSL 错误一律拦下。
                             *
                             * 只有"这台主机的证书过期"这一种情况能过：主机名精确匹配 +
                             * 过期天数在窗口内。放行时把实话告诉用户（[onCertNotice]），
                             * 不做成"证书错误已忽略"这种糊弄。
                             */
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                // SslCertificate 的 getter 是 getValidNotAfterDate()，Kotlin 侧属性名随之
                                val expiredAt =
                                    runCatching { error?.certificate?.validNotAfterDate?.time }.getOrNull()
                                val now = System.currentTimeMillis()
                                val tolerated =
                                    isCertDateError(error) &&
                                        ExpiredCertTolerance.shouldProceedOnSslError(error?.url, expiredAt, now)
                                if (tolerated) {
                                    handler?.proceed()
                                    onCertNotice(ExpiredCertTolerance.sslNotice(expiredAt ?: 0L, now))
                                } else {
                                    handler?.cancel()
                                    onError("HTTPS 证书校验失败（${error?.url ?: "未知地址"}）")
                                }
                            }
                        }
                }.also(onWebView)
        },
        update = { webView ->
            if (lastLoaded.value != request) {
                lastLoaded.value = request
                wafRetries[0] = 0
                loadWhenSized(webView, request.url)
            }
        },
    )
}
