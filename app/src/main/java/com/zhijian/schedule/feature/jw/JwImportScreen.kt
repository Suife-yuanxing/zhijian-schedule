package com.zhijian.schedule.feature.jw

import android.content.res.Configuration
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.jw.script.JwScriptRunner
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.CourseTheme
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓取失败等错误文案的强调色（与设计稿的红色一致） */
private val ErrorRed = Color(0xFFE5484D)

/**
 * 教务系统导入（WebView 版）：① 登录 → ② 进入学生课表 → ③ 抓取 → ④ 预览 → 应用。
 *
 * ## 版面预算：这一页真正的设计对象是「壳与内容的占比」
 * 这是一个把第三方网页装进自家 App 的页面，壳上的每一 dp 都是从网页身上切走的。
 * 改造前的实测（1080x2376、密度 480，即 360x792dp）：
 * - 顶栏以下到网页顶边之间，固定开销约 **187dp**（地址行 64 + 识别名 30 + 引导区 93）；
 * - 网页可视高度约 **439dp**，只占屏高的 55%；而教务登录页自身在 360dp 宽的手机上
 *   还有大片留白——一屏里两套「空」叠着，内容被挤成一条缝。
 *
 * 重排后同一区段压到约 **124dp**（地址行 60 + 引导区 64），网页升到约 **500dp（63%）**，
 * 且底部工具条同步收薄。省下来的高度全部还给网页，不是靠删信息换来的：
 * 识别结果从独立一行并进引导行右侧徽标，三行步骤文字压成「进度点 + 一行提示」，
 * 四档文案与判定口径逐字保留（见 [jwStageOf] / [jwStatusText]）。
 *
 * ## 三条视觉逻辑（来源见文末注释）
 * 1. **内容优先**：常驻 chrome 只保留「我在哪一步、下一步做什么、主行动在哪」，
 *    其余（脚本提示、错误、WAF 横幅）按需出现、贴着内容出现，不占常驻版面。
 * 2. **拇指区**：抓取课表是唯一主行动，落在右下方拇指自然弧线内，48dp 高（Fitts 定律）。
 * 3. **层次靠字重与留白，不靠描边堆叠**：改造前地址框、引导区、网页顶栏三条同重横线
 *    互相打架；现在描边只出现在输入条与按钮上，其余用间距分层。
 *
 * ## 文件分工
 * URL 判定在 [isJwLoginPage] 等（JwPageUrl.kt），阶段与文案在 JwImportStage.kt，
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
    var showSchoolPicker by remember { mutableStateOf(false) }
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
                    // 选校 = 填地址的快捷入口，与脚本管理同级放在顶栏，不占正文宽度
                    Text(
                        text = "选校",
                        color = colors.accent,
                        fontSize = 13.sp,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showSchoolPicker = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    TapIconButton(onClick = { showScriptPicker = true }) {
                        Icon(Icons.Filled.Build, contentDescription = "课程脚本", tint = colors.text)
                    }
                },
            )
        },
        // 真全面屏：insets 不在这里消费——网页内容一路铺到屏幕底（手势条后面），阅读区域最大化
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = colors.bg,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---------- ① 地址：一行紧凑输入条 ----------
            UrlBar(
                url = viewModel.url,
                onUrlChange = viewModel::onUrlChange,
                onOpen = viewModel::reload,
                colors = colors,
            )

            // HTTP 明文提示：默认只在地址是 http:// 时出现，一行说清风险与做法
            if (viewModel.url.trim().startsWith("http://", ignoreCase = true)) {
                Text(
                    text = "该地址是 HTTP 明文，登录凭据可能被同一网络中的他人看到；学校支持时请改用 HTTPS",
                    color = colors.accent,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                )
            }

            // 证书过期放行提示：不是失败（页面照常可用），但用户有权知道自己踩在一个
            // 过期证书上。用与上面 HTTP 提示同一档的字号，不额外占一整块版面。
            viewModel.certNotice?.let { notice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = notice,
                        color = colors.accent,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TapIconButton(onClick = viewModel::dismissCertNotice, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭证书提示",
                            tint = colors.textMuted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            // 步骤进度 + 两行下一步提示（识别的教务类型挂在进度行右端）
            GuideBar(
                colors = colors,
                onLoginPage = viewModel.onLoginPage,
                onLoggedInPage = viewModel.onLoggedInPage,
                onAuthserverPage = viewModel.onAuthserverPage,
                steps = viewModel.currentSteps,
                adapterName = viewModel.detectedAdapterName,
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

            // 顶壳与网页之间的分界：一条 1dp hairline 就够，不要加卡片底色——
            // 这里是「我们的界面」和「学校的网页」的交界，需要的是边界，不是又一层容器。
            HorizontalDivider(thickness = 1.dp, color = colors.border.copy(alpha = 0.7f))

            // ---------- ② 网页：登录 / 进课表页在这里完成 ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                WebViewBox(
                    request = viewModel.loadRequest,
                    onPageUrl = viewModel::onPageUrl,
                    onProgress = viewModel::onLoadProgress,
                    onError = viewModel::onLoadError,
                    onHttpError = viewModel::onHttpError,
                    onCertNotice = viewModel::onCertNotice,
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

            // ---------- ③ 抓取：提示与底部工具条 ----------
            // 底部工具条与网页之间同样用 hairline 划界：主行动区是常驻控件，
            // 让它有一条自己的边，比给它一整条底色更省高度也更有秩序。
            HorizontalDivider(thickness = 1.dp, color = colors.border.copy(alpha = 0.7f))
            JwImportFooter(
                colors = colors,
                notices =
                    JwFooterNotices(
                        scriptNotice = viewModel.scriptNotice,
                        onDismissNotice = viewModel::dismissScriptNotice,
                        errorMessage = viewModel.errorMessage,
                        needsLoginNote = needsLoginNote(viewModel),
                    ),
                autoSyncEnabled = viewModel.autoSyncEnabled,
                onAutoSyncChange = viewModel::setAutoSync,
                lastSyncHint = syncHint(viewModel.lastSyncTime),
                onFetch = viewModel::fetchNow,
                fetchEnabled = !viewModel.loading,
            )
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

    if (showSchoolPicker) {
        SchoolPickerDialog(
            onDismiss = { showSchoolPicker = false },
            onPick = { profile ->
                showSchoolPicker = false
                viewModel.onUrlChange(profile.entry())
                viewModel.reload()
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

/** 未登录时的补救说明：页面名 + 该校特有注意事项，拼成一句给用户照做 */
private fun needsLoginNote(viewModel: JwImportViewModel): String? {
    if (!viewModel.needsLogin) return null
    val steps = viewModel.currentSteps
    val page = steps?.timetablePage ?: DEFAULT_TIMETABLE_PAGE
    val note = steps?.note.orEmpty()
    val head = "请先在上方网页中登录，并进入「" + page + "」页面后重试。"
    return if (note.isBlank()) head else head + note
}

/** 底部第二行：有上次成功时间就报时间，否则说明这个开关在做什么 */
private fun syncHint(lastSyncTime: Long): String =
    if (lastSyncTime > 0) {
        "上次成功 " + formatTime(lastSyncTime)
    } else {
        "每日自动抓取课表变更"
    }

/**
 * 地址输入条：一行装下「地址 + 清空 + 选校 + 打开」。
 *
 * ## 为什么不用 OutlinedTextField
 * M3 的 OutlinedTextField 最小高度 56dp，外加浮动 label 吃掉的 16dp 顶部留白；
 * 改造前这一行实测 64dp，还带一整圈描边——它和下面的引导区、网页顶栏连成三条同重横线，
 * 「网页内容」被压成最下面的一条缝。
 *
 * 这里换成自绘单行条：
 * - 视觉高度 44dp（比 M3 输入框矮 12dp），外层 6dp 垂直 padding 让触控区达 56dp；
 * - 只有 1dp 描边，聚焦时描边转强调色——焦点状态靠颜色，不靠额外阴影；
 * - 装饰用 [BasicTextField] 的 decorationBox 自绘，输入、光标、选择、IME 全走系统默认语义；
 * - 只给「地址文本」本身让出最大宽度：「选校」移进顶栏（它是填地址的辅助动作，
 *   不该与地址抢同一行的横向空间），行内只留一个可点的清空。
 */
@Composable
private fun UrlBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onOpen: () -> Unit,
    colors: CourseColors,
) {
    val muted = jwMuted(colors)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val stroke by animateColorAsState(
        targetValue = if (focused) colors.accent else colors.border,
        animationSpec = tween(160),
        label = "urlStroke",
    )
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, stroke, shape)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { onOpen() }),
                interactionSource = source,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (url.isEmpty()) {
                            Text(
                                text = "教务系统地址",
                                color = muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            // 清空只在有内容时出现：一个入口一个作用，不做成常驻的双胞胎按钮
            AnimatedVisibility(
                visible = url.isNotEmpty(),
                enter = fadeIn(tween(Motion.DUR_FAST)),
                exit = fadeOut(tween(Motion.DUR_FAST)),
            ) {
                TapIconButton(onClick = { onUrlChange("") }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空地址",
                        tint = muted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        PrimaryPill(
            label = "打开",
            onClick = onOpen,
            colors = colors,
            minHeightDp = 44,
            horizontalPaddingDp = 16,
        )
    }
}

/**
 * 工具条上方那两块「临时信息」：脚本提示条与抓取失败说明。
 *
 * 打包成一个对象不是为了少写几个参数，而是因为它们**同生同灭**：
 * 都只在一次抓取之后出现，都贴着工具条上沿展开，都在下一次抓取开始时清空。
 * 放在一起后，[JwImportFooter] 的签名表达的是「常驻工具条 + 一串临时提示」这个结构，
 * 而不是把它压成十个并列参数。
 */
private data class JwFooterNotices(
    val scriptNotice: String?,
    val onDismissNotice: () -> Unit,
    val errorMessage: String?,
    val needsLoginNote: String?,
)

/**
 * 底部工具条：左侧「每日自动同步 + 上次成功」，右侧「抓取课表」。
 *
 * ## 与原版的差别
 * - 原版是一整条 52dp 的 surface 底色横条，左右两块权重相同，抓取只是普通 Button；
 * - 现在工具条本体 48dp，开关保持原尺寸（它有一个 48dp 的触控热区，压不小），
 *   抓取升为实心主色 + 图标 + 48dp 高，落在拇指自然弧线内——Fitts 定律：目标越大越近，
 *   命中时间越短；底部右角同时也是「无限边」，手指不需要精确落点。
 *
 * 失败与脚本提示不从工具条内部展开，而是抬到它**上方**、贴着内容出现：
 * 「刚刚发生了什么」属于临时信息，不该和常驻控件抢同一块版面。
 */
@Composable
private fun JwImportFooter(
    colors: CourseColors,
    notices: JwFooterNotices,
    autoSyncEnabled: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    lastSyncHint: String,
    onFetch: () -> Unit,
    fetchEnabled: Boolean,
) {
    val muted = jwMuted(colors)
    Column(Modifier.fillMaxWidth().imePadding()) {
        AnimatedVisibility(
            visible = notices.scriptNotice != null,
            enter = expandVertically(tween(Motion.DUR_MEDIUM)) + fadeIn(tween(Motion.DUR_FAST)),
            exit = shrinkVertically(tween(Motion.DUR_FAST)) + fadeOut(tween(Motion.DUR_FAST)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = notices.scriptNotice.orEmpty(),
                    color = colors.accent,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TapIconButton(onClick = notices.onDismissNotice, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭提示",
                        tint = muted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = notices.errorMessage != null,
            enter = expandVertically(tween(Motion.DUR_MEDIUM)) + fadeIn(tween(Motion.DUR_FAST)),
            exit = shrinkVertically(tween(Motion.DUR_FAST)) + fadeOut(tween(Motion.DUR_FAST)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = notices.errorMessage.orEmpty(),
                    color = ErrorRed,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                notices.needsLoginNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(2.dp))
                    Text(text = note, color = muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每日自动同步", color = colors.text, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    JellySwitch(checked = autoSyncEnabled, onCheckedChange = onAutoSyncChange)
                }
                Text(
                    text = lastSyncHint,
                    color = muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            PrimaryPill(
                label = "抓取课表",
                onClick = onFetch,
                colors = colors,
                minHeightDp = 48,
                horizontalPaddingDp = 18,
                leading = {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = if (fetchEnabled) colors.onAccent else colors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                },
                enabled = fetchEnabled,
            )
        }
    }
}

/**
 * 本页的主行动按钮（打开 / 抓取课表）：实心主色胶囊 + 按下回弹，尺寸由调用方给。
 *
 * 两个主行动共用同一个组件，是为了让它们在同一次浏览里**读起来是同一种东西**：
 * 同色、同圆角语言、同 14sp 半粗字、同回弹曲线。差异只在高度（44 / 48dp）——
 * 一个在输入行里、一个在拇指区，尺寸跟着容器走，不必强行拉平。
 */
@Composable
private fun PrimaryPill(
    label: String,
    onClick: () -> Unit,
    colors: CourseColors,
    minHeightDp: Int,
    horizontalPaddingDp: Int,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .heightIn(min = minHeightDp.dp)
            .clip(shape)
            .background(if (enabled) colors.accent else colors.surfaceVariant)
            .buttonFeedback(source, pressedScale = 0.96f, enabled = enabled)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ).padding(horizontal = horizontalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke()
        Text(
            text = label,
            color = if (enabled) colors.onAccent else colors.textMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    return fmt.format(Date(millis))
}

// ---------- 预览：把「壳」单独看一遍，不必连真机就能核对占比 ----------

@Preview(name = "导入壳·亮", widthDp = 393, heightDp = 420, showBackground = true)
@Composable
private fun JwImportChromePreview() {
    CourseTheme(presetId = ThemePresets.paper.id, darkMode = 1) {
        JwChromePreviewBody()
    }
}

@Preview(
    name = "导入壳·暗",
    widthDp = 393,
    heightDp = 420,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JwImportChromeDarkPreview() {
    CourseTheme(presetId = ThemePresets.paper.id, darkMode = 2) {
        JwChromePreviewBody()
    }
}

@Composable
private fun JwChromePreviewBody() {
    val colors = LocalCourseColors.current
    Column(Modifier.fillMaxWidth().background(colors.bg)) {
        UrlBar(
            url = "https://jw.dgpt.edu.cn/jsxsd/",
            onUrlChange = {},
            onOpen = {},
            colors = colors,
        )
        GuideBar(
            colors = colors,
            onLoginPage = true,
            onLoggedInPage = false,
            onAuthserverPage = false,
            steps =
                com.zhijian.schedule.core.jw
                    .AdapterSteps(timetablePage = "学生课表"),
        )
        Box(Modifier.fillMaxWidth().height(120.dp).background(colors.surfaceVariant))
    }
}
