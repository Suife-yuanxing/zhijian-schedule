package com.zhijian.schedule.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.pressScale
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
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新", color = colors.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("当前版本 v${BuildConfig.VERSION_NAME}", color = colors.textMuted, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                UpdateStatusLine(ui)

                val download = ui.download
                if (download is DownloadState.Running) {
                    Spacer(Modifier.height(10.dp))
                    if (download.totalBytes != null) {
                        LinearProgressIndicator(
                            progress = {
                                (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                            trackColor = colors.border,
                        )
                    } else {
                        // 响应没有长度信息：不确定进度，不显示假百分比
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                            trackColor = colors.border,
                        )
                    }
                }

                ui.message?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(message, color = colors.accent, fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("镜像加速", color = colors.text, fontSize = 13.sp)
                        Text(mirrorHint(ui), color = colors.textMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = ui.mirrorsEnabled,
                        onCheckedChange = { viewModel.setMirrorsEnabled(it) },
                    )
                }

                val info = ui.updateInfo
                if (info?.apkUrl != null) {
                    TextButton(onClick = { openInBrowser(context, info) }) {
                        Text("直连不通？用浏览器下载", color = colors.accent, fontSize = 12.sp)
                    }
                }

                // 下载中隐藏：分片正被写入，而且此时 updateCacheBytes 还是上一次终态的旧值
                if (ui.updateCacheBytes > 0L && download !is DownloadState.Running) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(
                            "清理下载缓存（${ResumableDownload.formatBytes(ui.updateCacheBytes)}）",
                            color = colors.textMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val pageUrl = ui.updateInfo?.pageUrl
                if (!pageUrl.isNullOrBlank()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }
                    }) { Text("打开发布页") }
                    Spacer(Modifier.width(2.dp))
                }
                when (val download = ui.download) {
                    is DownloadState.Running -> ActionButton("取消") { viewModel.cancelDownload() }
                    is DownloadState.Paused -> ActionButton("继续下载") { viewModel.downloadUpdate() }
                    is DownloadState.Ready -> ActionButton("安装") { viewModel.installUpdate() }
                    is DownloadState.Failed -> ActionButton(if (download.canResume) "重试" else "重新下载") {
                        viewModel.downloadUpdate()
                    }
                    DownloadState.Idle -> if (ui.updateInfo?.apkUrl != null) {
                        ActionButton("下载") { viewModel.downloadUpdate() }
                    } else {
                        ActionButton(checkLabel(ui), enabled = !ui.busy) { viewModel.checkUpdate() }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理下载缓存") },
            text = {
                Text(
                    "将删除已下载的安装包与未完成的下载进度" +
                        "（共 ${ResumableDownload.formatBytes(ui.updateCacheBytes)}）。清理后需要重新下载才能安装。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUpdateCache()
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 窗口的状态行：下载状态下优先显示下载信息，否则显示检查结果 */
@Composable
private fun UpdateStatusLine(ui: UpdateUiState) {
    val colors = LocalCourseColors.current
    val download = ui.download
    val (text, emphasized) = when {
        download is DownloadState.Running -> runningLine(download) to true
        download is DownloadState.Paused ->
            "已取消 · 已保留 ${ResumableDownload.formatBytes(download.downloadedBytes)} 进度" to false
        download is DownloadState.Ready -> "下载完成，待安装（${ResumableDownload.formatBytes(download.sizeBytes)}）" to true
        download is DownloadState.Failed -> {
            val hint = if (!ui.mirrorsEnabled) " · 可开启镜像加速或浏览器下载" else ""
            (download.reason + hint) to false
        }
        ui.updateError != null -> "检查更新失败：${ui.updateError}" to false
        ui.updateInfo != null -> versionLine(ui.updateInfo) to true
        ui.updateChecked -> "已是最新版本 v${BuildConfig.VERSION_NAME}" to false
        else -> "通过 GitHub Releases 分发" to false
    }
    Text(text, color = if (emphasized) colors.text else colors.textMuted, fontSize = 12.sp)
}

private fun runningLine(state: DownloadState.Running): String {
    val head = state.totalBytes
        ?.let { "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}" }
        ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    // 直连时 sourceLabel 为 null，默认路径不显示来源
    val source = state.sourceLabel?.let { " · 经 $it" }.orEmpty()
    return head + source + (ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty())
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

private fun versionLine(info: UpdateChecker.UpdateInfo): String = if (info.apkUrl == null) {
    "发现新版本 v${info.version}（该版本未提供安装包）"
} else {
    "发现新版本 v${info.version}" +
        (info.apkSize?.takeIf { it > 0 }?.let { " · 大小 ${ResumableDownload.formatBytes(it)}" } ?: "")
}

private fun checkLabel(ui: UpdateUiState): String = when {
    ui.busy -> "检查中…"
    ui.updateChecked -> "重新检查"
    else -> "检查更新"
}

/** 窗口里的主操作按钮：统一的按压缩放反馈 */
@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = Modifier.pressScale(source, pressedScale = 0.96f, enabled = enabled),
    ) {
        Text(label)
    }
}
