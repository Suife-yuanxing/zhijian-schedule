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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.script.JwScriptRunner
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    c = /initial-scale\s*=/.test(c) ? c.replace(/initial-scale\s*=\s*[0-9.]+/g, 'initial-scale=' + s) : c + ', initial-scale=' + s;
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
    val host = runCatching { java.net.URI(entryUrl).host }.getOrNull() ?: return ""
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
    if (/50[234]\s*(Bad Gateway|Gateway Time-?out|Service Unavailable)/i.test(s) || s.indexOf('云防护节点') >= 0) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwImportScreen(
    onBack: () -> Unit,
    onOpenScripts: () -> Unit = {},
    viewModel: JwImportViewModel = hiltViewModel(),
) {
    val colors = LocalCourseColors.current
    val preview = viewModel.preview
    val haptics = LocalHapticFeedback.current
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var showScriptPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runScript(option: ScriptOption) {
        val wv = webView
        if (wv == null) {
            viewModel.onScriptFailed("网页尚未就绪，请稍候重试")
            return
        }
        scope.launch {
            viewModel.setScriptBusy(true)
            try {
                val source = viewModel.scriptSource(option.id)
                if (source == null) {
                    viewModel.onScriptFailed("脚本内容缺失，请重新安装")
                    return@launch
                }
                val weeks = viewModel.currentTotalWeeks()
                when (val run = JwScriptRunner.run(wv, source, option.manifest, viewModel.pageUrl, weeks)) {
                    is JwScriptRunner.RunResult.Ok -> viewModel.onScriptEnvelope(run.envelopeJson)
                    is JwScriptRunner.RunResult.Failed -> viewModel.onScriptFailed(run.message)
                }
            } catch (e: IllegalStateException) {
                viewModel.onScriptFailed(e.message ?: "脚本执行出错")
            } finally {
                viewModel.setScriptBusy(false)
            }
        }
    }

    // 抓取完成时给一个确认轻震（弹窗同时出现）
    LaunchedEffect(viewModel.done) {
        if (viewModel.done) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(
                title = "教务系统导入",
                onBack = onBack,
                actions = {
                    TapIconButton(onClick = { showScriptPicker = true }) {
                        Icon(Icons.Filled.Build, contentDescription = "课程脚本", tint = colors.text)
                    }
                },
            )
        },
        containerColor = colors.bg,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = viewModel.url,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("教务地址", fontSize = 12.sp) },
                    placeholder = { Text("含 /jsxsd/ 或 /jwglxt/ 的完整地址", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val openSource = remember { MutableInteractionSource() }
                Button(
                    onClick = viewModel::reload,
                    interactionSource = openSource,
                    modifier = Modifier.buttonFeedback(openSource, pressedScale = 0.96f),
                ) { Text("打开", fontSize = 14.sp) }
            }

            val adapterName = viewModel.detectedAdapterName
            Text(
                text = adapterName?.let { "识别为：$it" }
                    ?: "未识别教务类型（当前支持：强智 jsxsd、正方新版）",
                color = if (adapterName != null) colors.accent else colors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            if (viewModel.url.trim().startsWith("http://", ignoreCase = true)) {
                Text(
                    text = "该地址使用 HTTP 明文传输，登录凭据可能被同一网络中的他人看到；学校支持 HTTPS 时请改用 HTTPS",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            GuideBar(
                colors = colors,
                onLoginPage = viewModel.onLoginPage,
                onLoggedInPage = viewModel.onLoggedInPage,
                onAuthserverPage = viewModel.onAuthserverPage,
            )

            // 仅在学校本地登录入口返回 5xx（源站超时/被 WAF 拦截）时，才露出统一认证兜底入口
            if (viewModel.httpErrorHint >= 500 && viewModel.ssoEntry != null) {
                TapTextButton(
                    onClick = viewModel::openSsoLogin,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "本地登录打不开？改用统一认证登录",
                        fontSize = 12.sp,
                        color = colors.accent,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                WebViewBox(
                    request = viewModel.loadRequest,
                    onPageUrl = viewModel::onPageUrl,
                    onProgress = viewModel::onLoadProgress,
                    onError = viewModel::onLoadError,
                    onHttpError = viewModel::onHttpError,
                    onWebView = { webView = it },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
                TopProgressBar(
                    visible = viewModel.loadProgress in 1..99,
                    progress = viewModel.loadProgress / 100f,
                    colors = colors,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                PageErrorOverlay(
                    error = viewModel.pageError,
                    onReload = viewModel::reload,
                    colors = colors,
                )
                LoadingOverlay(visible = viewModel.loading || viewModel.scriptRunning, colors = colors)
                HttpErrorBanner(
                    code = viewModel.httpErrorHint,
                    onDismiss = viewModel::dismissHttpError,
                    colors = colors,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            viewModel.scriptNotice?.let { message ->
                Text(
                    text = message,
                    color = colors.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            viewModel.errorMessage?.let { message ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        text = message,
                        color = androidx.compose.ui.graphics.Color(0xFFE5484D),
                        fontSize = 12.sp,
                    )
                    if (viewModel.needsLogin) {
                        Text(
                            text = "提示：请先在上方网页中登录，并进入「学生课表」页面后重试。",
                            color = colors.textMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("每日自动同步", color = colors.text, fontSize = 13.sp)
                    Text(
                        text = if (viewModel.lastSyncTime > 0) {
                            "上次成功：${formatTime(viewModel.lastSyncTime)}"
                        } else {
                            "每日自动抓取课表变更（方案 4.10）"
                        },
                        color = colors.textMuted,
                        fontSize = 11.sp,
                    )
                }
                JellySwitch(
                    checked = viewModel.autoSyncEnabled,
                    onCheckedChange = {
                        viewModel.setAutoSync(it)
                    },
                )
                Spacer(Modifier.width(10.dp))
                val fetchSource = remember { MutableInteractionSource() }
                Button(
                    onClick = viewModel::fetchNow,
                    enabled = !viewModel.loading,
                    interactionSource = fetchSource,
                    modifier = Modifier.buttonFeedback(fetchSource, pressedScale = 0.96f, enabled = !viewModel.loading),
                ) {
                    Text("抓取课表", fontSize = 14.sp)
                }
            }
        }
    }

    if (viewModel.done) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBack,
            title = { Text("导入完成") },
            text = { Text("课表已更新，可在周视图查看。") },
            confirmButton = { TapTextButton(onClick = onBack) { Text("好的") } },
        )
    }

    if (showScriptPicker) {
        ScriptPickerDialog(
            pageUrl = viewModel.pageUrl,
            optionsProvider = { viewModel.scriptOptions(viewModel.pageUrl) },
            onDismiss = { showScriptPicker = false },
            onManage = {
                showScriptPicker = false
                onOpenScripts()
            },
            onRun = { option ->
                showScriptPicker = false
                runScript(option)
            },
        )
    }

    preview?.let { p ->
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = viewModel::dismissPreview,
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = "抓取结果 · ${p.summary()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(p.changes) { change ->
                        val label = when (change.kind) {
                            ImportChangeKind.ADDED -> "新增"
                            ImportChangeKind.CHANGED -> "变更"
                            ImportChangeKind.REMOVED -> "取消"
                            ImportChangeKind.UNCHANGED -> "无变化"
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                text = label,
                                color = colors.accent,
                                fontSize = 12.sp,
                                modifier = Modifier.width(40.dp),
                            )
                            Column {
                                Text(change.name, color = colors.text, fontSize = 14.sp)
                                if (change.detail.isNotBlank()) {
                                    Text(change.detail, color = colors.textMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.border)
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TapTextButton(onClick = viewModel::dismissPreview, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    val applySource = remember { MutableInteractionSource() }
                    Button(
                        onClick = viewModel::applyPreview,
                        interactionSource = applySource,
                        modifier = Modifier
                            .weight(1f)
                            .buttonFeedback(applySource, pressedScale = 0.97f),
                    ) {
                        Text("应用变更")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun GuideBar(
    colors: CourseColors,
    onLoginPage: Boolean,
    onLoggedInPage: Boolean,
    onAuthserverPage: Boolean,
) {
    val status = when {
        onLoggedInPage -> "已进入教务系统，点右下「抓取课表」" to colors.accent
        onAuthserverPage -> "请在「统一身份认证」页用学号 + 密码登录" to colors.accent
        onLoginPage -> "请用学号 / 密码登录；校外网络请先连校园 WiFi，或经 WebVPN 访问" to colors.accent
        else -> "登录后打开「学生课表」页面，再点「抓取课表」" to colors.textMuted
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("① 登录", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" → ", color = colors.textMuted, fontSize = 12.sp)
            Text("② 学生课表", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" → ", color = colors.textMuted, fontSize = 12.sp)
            Text("③ 抓取课表", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Text(status.first, color = status.second, fontSize = 11.sp)
    }
}

/** WebView 顶部加载进度条：淡入淡出，不打断网页本身 */
@Composable
private fun TopProgressBar(
    visible: Boolean,
    progress: Float,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = Motion.DurFast)),
        exit = fadeOut(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        modifier = modifier,
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.border,
        )
    }
}

/** 网页加载失败浮层 */
@Composable
private fun PageErrorOverlay(
    error: String?,
    onReload: () -> Unit,
    colors: CourseColors,
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DurFast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text("网页加载失败", color = colors.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error.orEmpty(),
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                TapButton(onClick = onReload) { Text("重新加载") }
            }
        }
    }
}

/** 阻塞式加载浮层 */
@Composable
private fun LoadingOverlay(visible: Boolean, colors: CourseColors) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DurFast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
        }
    }
}

/** 校外直连被 WAF 拦截时的顶部提示横幅：从上滑入，可关闭 */
@Composable
private fun HttpErrorBanner(
    code: Int,
    onDismiss: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = code >= 400,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        ) { -it } + fadeIn(tween(durationMillis = Motion.DurMedium)),
        exit = slideOutVertically(tween(durationMillis = Motion.DurMedium)) { -it } +
            fadeOut(tween(durationMillis = Motion.DurFast)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "学校服务器返回 $code：源站暂时不可用或校外网络被拦截。可改用统一认证登录（见上方），或连校园 WiFi / WebVPN 后重试",
                color = colors.text,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TapTextButton(onClick = onDismiss) { Text("知道了", fontSize = 12.sp) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBox(
    request: WebLoadRequest,
    onPageUrl: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit,
    onHttpError: (Int) -> Unit,
    onWebView: (android.webkit.WebView?) -> Unit = {},
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
                settings.databaseEnabled = true
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
                addJavascriptInterface(
                    com.zhijian.schedule.core.jw.script.JwScriptRunner.PaperJavascriptBridge(),
                    "PaperBridge",
                )
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))

/** 脚本选择底部弹层：命中当前站点的排前；底部入口进管理页（spec §4） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptPickerDialog(
    pageUrl: String,
    optionsProvider: suspend () -> List<ScriptOption>,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onRun: (ScriptOption) -> Unit,
) {
    val colors = LocalCourseColors.current
    val options = remember(pageUrl) { mutableStateOf<List<ScriptOption>?>(null) }
    LaunchedEffect(pageUrl) {
        options.value = optionsProvider()
    }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = "用脚本导入",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            val list = options.value
            when {
                list == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                }
                list.isEmpty() -> {
                    Text(
                        "还没有可用的课程脚本。可在「管理脚本」里导入 .js 或从仓库安装。",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
                else -> {
                    list.forEach { option ->
                        val enabled = option.runnable
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onRun(option) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = option.name,
                                color = if (enabled) colors.text else colors.textMuted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = option.subtitle(),
                                color = colors.textMuted,
                                fontSize = 11.sp,
                            )
                            if (option.entryHint.isNotBlank()) {
                                Text(
                                    text = option.entryHint,
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            if (option.matched) {
                                Text(
                                    text = "适用于当前站点",
                                    color = colors.accent,
                                    fontSize = 11.sp,
                                )
                            }
                            if (!option.enabled) {
                                Text(text = "已在管理页停用", color = colors.textMuted, fontSize = 11.sp)
                            } else if (!option.matched && option.hosts.isNotEmpty()) {
                                Text(
                                    text = "当前页面不属于该脚本适用的站点",
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TapTextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                TapTextButton(onClick = onManage, modifier = Modifier.weight(1f)) { Text("管理脚本…") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
