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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.script.JwScriptRunner
import com.zhijian.schedule.core.net.ExpiredCertTolerance
import org.json.JSONObject
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

/** WAF 5xx 多为瞬时抖动：自动重试两次再交给用户，避免一进来就是错误页 */
private const val WAF_MAX_RETRIES = 2
private const val WAF_RETRY_DELAY_MS = 1500L

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
    modifier: Modifier = Modifier,
) {
    val lastLoaded = remember { mutableStateOf<WebLoadRequest?>(null) }
    // WAF 5xx 自动重试计数；每次新的加载请求归零
    val wafRetries = remember { IntArray(1) }
    val ssoEntry =
        remember(request.url) {
            runCatching { JwAdapterRegistry.forUrl(request.url)?.ssoEntry(request.url) }.getOrNull()
        }
    val ssoJs = remember(ssoEntry) { ssoRecoverJs(ssoEntry) }
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
                                if (ssoJs.isNotEmpty()) view?.evaluateJavascript(ssoJs, null)
                                view?.evaluateJavascript(JW_SHELL_FIX_JS, null)
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
