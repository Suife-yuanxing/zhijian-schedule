package com.zhijian.schedule.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.update.DownloadSourceSet
import com.zhijian.schedule.update.DownloadState
import com.zhijian.schedule.update.ResumableDownload
import com.zhijian.schedule.update.UpdateChecker

/**
 * 检查更新窗口：版本状态 + 应用内下载（进度 / 续传 / 校验后的安装）+ 下载选项。
 * 状态语义沿用更新卡片：未检查/检查中/已最新/检查失败/有新版本/下载中/已取消/待安装/校验失败。
 */
@Composable
fun UpdateDialog(
    onDismiss: () -> Unit,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val ui = viewModel.uiState
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshFromDisk() }

    PaperDialog(onDismissRequest = onDismiss) {
        DialogHeader(onDismiss)
        Spacer(Modifier.height(16.dp))
        UpdateStatusCard(ui)
        ui.message?.let { message ->
            Spacer(Modifier.height(10.dp))
            MessageBanner(message)
        }
        Spacer(Modifier.height(18.dp))
        UpdateActionButton(ui, viewModel)
        DialogFooter(ui, onDismiss, context)
        if (showsDownloadOptions(ui)) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = LocalCourseColors.current.border.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            DownloadOptions(ui, viewModel, context, onConfirmClear = { confirmClear = true })
        }
    }

    if (confirmClear) {
        ClearCacheDialog(
            bytes = ui.updateCacheBytes,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearUpdateCache()
            },
        )
    }
}

/** 纸本风格的对话框壳：圆角卡片 + 细描边 + 可滚动内容 */
@Composable
private fun PaperDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCourseColors.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                contentColor = colors.text,
                border = BorderStroke(1.dp, colors.border.copy(alpha = 0.7f)),
                shadowElevation = 20.dp,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun DialogHeader(onDismiss: () -> Unit) {
    val colors = LocalCourseColors.current
    val dark = LocalIsDark.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent.copy(alpha = if (dark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("检查更新", style = MaterialTheme.typography.titleLarge, color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text("当前版本 v${BuildConfig.VERSION_NAME}", color = colors.textMuted, fontSize = 12.sp)
        }
        TapIconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭",
                tint = colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(ui: UpdateUiState) {
    val download = ui.download
    val info = ui.updateInfo
    when {
        ui.busy -> CheckingCard()
        download is DownloadState.Running -> RunningCard(download)
        download is DownloadState.Paused -> PausedCard(download)
        download is DownloadState.Ready -> ReadyCard(download)
        download is DownloadState.Failed -> FailedCard(download, ui.mirrorsEnabled)
        ui.updateError != null -> CheckFailedCard(ui.updateError)
        info != null -> UpdateAvailableCard(info)
        ui.updateChecked -> LatestCard()
        else -> CheckingCard(quiet = true)
    }
}

@Composable
private fun StatusCard(
    container: Color = LocalCourseColors.current.surfaceVariant,
    border: Color = LocalCourseColors.current.border.copy(alpha = 0.55f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), content = content)
    }
}

@Composable
private fun CheckingCard(quiet: Boolean = false) {
    val colors = LocalCourseColors.current
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (quiet) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (quiet) "准备检查更新" else "正在检查更新",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text("从 GitHub Releases 获取版本信息", color = colors.textMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UpdateAvailableCard(info: UpdateChecker.UpdateInfo) {
    val colors = LocalCourseColors.current
    val dark = LocalIsDark.current
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.accent.copy(alpha = if (dark) 0.2f else 0.12f),
                    ) {
                        Text(
                            "新版本",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "v${info.version}",
                        color = colors.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    buildString {
                        append("GitHub Releases")
                        info.apkSize?.takeIf { it > 0L }?.let {
                            append(" · ").append(ResumableDownload.formatBytes(it))
                        }
                    },
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun RunningCard(state: DownloadState.Running) {
    val colors = LocalCourseColors.current
    val fraction = state.totalBytes
        ?.takeIf { it > 0L }
        ?.let { (state.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "正在下载更新",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text("v${state.version}", color = colors.textMuted, fontSize = 12.sp)
            }
            fraction?.let {
                Text(
                    "${(it * 100).toInt()}%",
                    color = colors.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (fraction != null) ProgressBar(fraction) else IndeterminateBar()
        Spacer(Modifier.height(8.dp))
        Text(
            runningDetail(state),
            color = colors.textMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun PausedCard(state: DownloadState.Paused) {
    val colors = LocalCourseColors.current
    val fraction = state.totalBytes
        ?.takeIf { it > 0L }
        ?.let { (state.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    StatusCard {
        Text(
            "下载已暂停",
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "已保留 ${ResumableDownload.formatBytes(state.downloadedBytes)} 进度，可继续下载",
            color = colors.textMuted,
            fontSize = 12.sp,
        )
        if (fraction != null) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(fraction)
        }
    }
}

@Composable
private fun ReadyCard(state: DownloadState.Ready) {
    val colors = LocalCourseColors.current
    val dark = LocalIsDark.current
    StatusCard(
        container = colors.accent.copy(alpha = if (dark) 0.18f else 0.1f),
        border = colors.accent.copy(alpha = 0.28f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "下载完成，待安装",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "v${state.version} · ${ResumableDownload.formatBytes(state.sizeBytes)}",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun FailedCard(state: DownloadState.Failed, mirrorsEnabled: Boolean) {
    val hint = if (!mirrorsEnabled && isSourceFailure(state.reason)) {
        "可开启镜像加速，或用浏览器下载"
    } else {
        null
    }
    ProblemCard(title = "下载失败", detail = state.reason, hint = hint)
}

@Composable
private fun CheckFailedCard(detail: String) {
    ProblemCard(title = "检查更新失败", detail = detail, hint = null)
}

@Composable
private fun ProblemCard(title: String, detail: String, hint: String?) {
    val colors = LocalCourseColors.current
    val error = MaterialTheme.colorScheme.error
    val dark = LocalIsDark.current
    StatusCard(
        container = error.copy(alpha = if (dark) 0.16f else 0.07f),
        border = error.copy(alpha = 0.22f),
    ) {
        Row {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(detail, color = colors.text, fontSize = 12.5.sp, lineHeight = 18.sp)
                hint?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = colors.textMuted, fontSize = 11.5.sp)
                }
            }
        }
    }
}

@Composable
private fun LatestCard() {
    val colors = LocalCourseColors.current
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "已是最新版本",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text("v${BuildConfig.VERSION_NAME}", color = colors.textMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MessageBanner(message: String) {
    val colors = LocalCourseColors.current
    val dark = LocalIsDark.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.accent.copy(alpha = if (dark) 0.16f else 0.08f),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(message, color = colors.text, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val colors = LocalCourseColors.current
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320, easing = Motion.EaseOut),
        label = "updateProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.border),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(colors.accent),
        )
    }
}

@Composable
private fun IndeterminateBar() {
    val colors = LocalCourseColors.current
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = colors.accent,
        trackColor = colors.border,
    )
}

@Composable
private fun UpdateActionButton(ui: UpdateUiState, viewModel: UpdateViewModel) {
    val download = ui.download
    when {
        download is DownloadState.Running -> OutlinedAction(
            label = "取消下载",
            onClick = { viewModel.cancelDownload() },
        )
        download is DownloadState.Paused -> FilledAction(
            label = "继续下载",
            onClick = { viewModel.downloadUpdate() },
        )
        download is DownloadState.Ready -> FilledAction(
            label = "安装更新",
            onClick = { viewModel.installUpdate() },
        )
        download is DownloadState.Failed -> FilledAction(
            label = if (download.canResume) "继续下载" else "重新下载",
            onClick = { viewModel.downloadUpdate() },
        )
        ui.updateInfo?.apkUrl != null -> FilledAction(
            label = "下载更新",
            onClick = { viewModel.downloadUpdate() },
        )
        else -> FilledAction(
            label = checkLabel(ui),
            onClick = { viewModel.checkUpdate() },
            enabled = !ui.busy,
        )
    }
}

@Composable
private fun DialogFooter(ui: UpdateUiState, onDismiss: () -> Unit, context: Context) {
    val colors = LocalCourseColors.current
    val pageUrl = ui.updateInfo?.pageUrl
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!pageUrl.isNullOrBlank()) {
            TapTextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }
            }) {
                Text("打开发布页", color = colors.accent, fontSize = 12.5.sp)
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }
        TapTextButton(onClick = onDismiss) {
            Text("关闭", color = colors.textMuted, fontSize = 12.5.sp)
        }
    }
}

/** 下载选项只在真的涉及安装包时出现，已最新时不要用镜像/清理占版面 */
private fun showsDownloadOptions(ui: UpdateUiState): Boolean =
    ui.updateInfo?.apkUrl != null || ui.updateCacheBytes > 0L

@Composable
private fun DownloadOptions(
    ui: UpdateUiState,
    viewModel: UpdateViewModel,
    context: Context,
    onConfirmClear: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    val download = ui.download
    val info = ui.updateInfo
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("镜像加速", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    mirrorHint(ui),
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = ui.mirrorsEnabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.setMirrorsEnabled(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info?.apkUrl != null) {
                TapTextButton(onClick = { openInBrowser(context, info) }) {
                    Text("用浏览器下载", color = colors.accent, fontSize = 12.5.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (ui.updateCacheBytes > 0L && download !is DownloadState.Running) {
                TapTextButton(onClick = onConfirmClear) {
                    Text(
                        "清理缓存 ${ResumableDownload.formatBytes(ui.updateCacheBytes)}",
                        color = colors.textMuted,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearCacheDialog(
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalCourseColors.current
    PaperDialog(onDismissRequest = onDismiss) {
        Text("清理下载缓存", style = MaterialTheme.typography.titleLarge, color = colors.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "将删除已下载的安装包与未完成的下载进度（共 ${ResumableDownload.formatBytes(bytes)}）。" +
                "清理后需要重新下载才能安装。",
            color = colors.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedAction(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            FilledAction(
                label = "清理",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 窗口里的主操作按钮：统一的按压缩放反馈 */
@Composable
private fun FilledAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .buttonFeedback(source, pressedScale = 0.97f, enabled = enabled, hapticType = HapticFeedbackType.Confirm),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.35f),
            disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
        ),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutlinedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .buttonFeedback(source, pressedScale = 0.97f),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textMuted),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

private fun runningDetail(state: DownloadState.Running): String {
    val size = state.totalBytes?.let {
        "${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}"
    } ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    val speed = state.bytesPerSecond
        .takeIf { it > 0L }
        ?.let { " · ${ResumableDownload.formatBytes(it)}/s" }
        .orEmpty()
    val eta = ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty()
    val source = state.sourceLabel?.let { " · 经 $it" }.orEmpty()
    return size + speed + eta + source
}

/** 镜像提示：先说合法性（有没有官方摘要），再说用途（实测镜像更慢，只当退路） */
private fun mirrorHint(ui: UpdateUiState): String {
    // 还没有检查到新版本时没有 digest 可谈，别误报「该版本未提供官方摘要」
    if (ui.updateInfo == null) {
        return if (ui.mirrorsEnabled) {
            "有新版本时，直连不通会自动换镜像重试（推荐）"
        } else {
            "关闭中：有新版本时只用 GitHub 直连"
        }
    }
    val reason = DownloadSourceSet.mirrorDisabledReason(
        digest = ui.updateInfo.apkDigest,
        mirrorsEnabled = ui.mirrorsEnabled,
    )
    return when (reason) {
        null -> "直连不通时自动换镜像重试（推荐）"
        "镜像加速已关闭" -> "关闭中：只用 GitHub 直连（可用浏览器下载兜底）"
        // reason 已经是完整句（含「已禁用镜像」），直接用它，别再拼「本次只用直连」造成重复
        else -> reason
    }
}

/**
 * 浏览器下载兜底：优先用带官方摘要的镜像地址。
 * 应用内下载走 FGS，系统省电策略可能中断；浏览器交给系统下载器，成功率更高。
 */
private fun openInBrowser(context: Context, info: UpdateChecker.UpdateInfo) {
    val directUrl = info.apkUrl ?: return
    val mirror = DownloadSourceSet.availableSources(
        directUrl = directUrl,
        digest = info.apkDigest,
        mirrors = DownloadSourceSet.MIRRORS,
    ).firstOrNull { it.id != DownloadSourceSet.DIRECT_ID }
    val target = mirror?.url ?: directUrl
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
}

private fun checkLabel(ui: UpdateUiState): String = when {
    ui.busy -> "检查中…"
    ui.updateChecked -> "重新检查"
    else -> "检查更新"
}
