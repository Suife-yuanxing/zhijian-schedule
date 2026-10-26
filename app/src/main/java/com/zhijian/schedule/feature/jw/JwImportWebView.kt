package com.zhijian.schedule.feature.jw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
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
import java.net.URI
import org.json.JSONObject

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
    onWebView: (WebView?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lastLoaded = remember { mutableStateOf<WebLoadRequest?>(null) }
    // WAF 5xx 自动重试计数；每次新的加载请求归零
    val wafRetries = remember { IntArray(1) }
    val ssoEntry = remember(request.url) {
        runCatching { JwAdapterRegistry.forUrl(request.url)?.ssoEntry(request.url) }.getOrNull()
    }
    val ssoJs = remember(ssoEntry) { ssoRecoverJs(ssoEntry) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
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
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
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
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        url?.takeIf { it.isNotBlank() }?.let(onPageUrl)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        url?.takeIf { it.isNotBlank() }?.let(onPageUrl)
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

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.cancel()
                        onError("HTTPS 证书校验失败（${error?.url ?: "未知地址"}）")
                    }
                }
            }.also(onWebView)
        },
        update = { webView ->
            if (lastLoaded.value != request) {
                lastLoaded.value = request
                wafRetries[0] = 0
                webView.loadUrl(request.url)
            }
        },
    )
}
