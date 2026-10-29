package com.zhijian.schedule.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.update.DownloadState

/**
 * 检查更新窗口：版本状态 + 应用内下载（进度 / 续传 / 校验后的安装）+ 下载选项。
 * 状态语义沿用更新卡片：未检查/检查中/已最新/检查失败/有新版本/下载中/已取消/待安装/校验失败。
 *
 * 本文件只保留窗口骨架与接线：状态卡片在 UpdateStatusSections.kt，
 * 下载选项与清理缓存在 UpdateDownloadSections.kt，按钮在 UpdateActionButtons.kt。
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

/** 纸本风格的对话框壳：圆角卡片 + 细描边 + 可滚动内容（清理缓存确认框也用它） */
@Composable
internal fun PaperDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCourseColors.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                contentColor = colors.text,
                border = BorderStroke(1.dp, colors.border.copy(alpha = 0.7f)),
                shadowElevation = 20.dp,
            ) {
                Column(
                    modifier =
                        Modifier
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
            modifier =
                Modifier
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

/** 窗口的主操作：按当前状态给唯一一个动作（取消 / 继续 / 安装 / 下载 / 检查） */
@Composable
private fun UpdateActionButton(
    ui: UpdateUiState,
    viewModel: UpdateViewModel,
) {
    val download = ui.download
    when {
        download is DownloadState.Running ->
            OutlinedAction(
                label = "取消下载",
                onClick = { viewModel.cancelDownload() },
            )
        download is DownloadState.Paused ->
            FilledAction(
                label = "继续下载",
                onClick = { viewModel.downloadUpdate() },
            )
        download is DownloadState.Ready ->
            FilledAction(
                label = "安装更新",
                onClick = { viewModel.installUpdate() },
            )
        download is DownloadState.Failed ->
            FilledAction(
                label = if (download.canResume) "继续下载" else "重新下载",
                onClick = { viewModel.downloadUpdate() },
            )
        ui.updateInfo?.apkUrl != null ->
            FilledAction(
                label = "下载更新",
                onClick = { viewModel.downloadUpdate() },
            )
        else ->
            FilledAction(
                label = checkLabel(ui),
                onClick = { viewModel.checkUpdate() },
                enabled = !ui.busy,
            )
    }
}

@Composable
private fun DialogFooter(
    ui: UpdateUiState,
    onDismiss: () -> Unit,
    context: Context,
) {
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
private fun showsDownloadOptions(ui: UpdateUiState): Boolean = ui.updateInfo?.apkUrl != null || ui.updateCacheBytes > 0L

private fun checkLabel(ui: UpdateUiState): String =
    when {
        ui.busy -> "检查中…"
        ui.updateChecked -> "重新检查"
        else -> "检查更新"
    }
