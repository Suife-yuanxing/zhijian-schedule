package com.zhijian.schedule.feature.jw

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.jw.script.JwScriptRunner
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 抓取失败等错误文案的强调色（与设计稿的红色一致） */
private val ErrorRed = Color(0xFFE5484D)

/**
 * 教务系统导入（WebView 版）：① 登录 → ② 进入学生课表 → ③ 抓取 → ④ 预览 → 应用。
 *
 * 本文件只做接线与步骤编排：URL 判定在 [isJwLoginPage] 等（JwPageUrl.kt），
 * WebView 宿主与注入脚本在 JwImportWebView.kt，状态/反馈区块在 JwImportStatusViews.kt，
 * 脚本选择在 ScriptPickerDialog.kt，预览与完成提示在 JwImportResult.kt，
 * 导入状态机在 [JwImportViewModel]。
 */
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
    var webView by remember { mutableStateOf<WebView?>(null) }
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
            // ---------- ① 地址与登录 ----------
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

            // ---------- ② 网页：登录 / 进课表页在这里完成 ----------
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

            // ---------- ③ 抓取：脚本提示与错误提示 ----------
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
                        color = ErrorRed,
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

    // ---------- ④ 结果与预览 ----------
    if (viewModel.done) {
        JwImportDoneDialog(onDismiss = onBack)
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
        JwPreviewSheet(
            preview = p,
            onDismiss = viewModel::dismissPreview,
            onApply = viewModel::applyPreview,
        )
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))
