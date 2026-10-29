package com.zhijian.schedule.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.update.DownloadSourceSet
import com.zhijian.schedule.core.update.DownloadState
import com.zhijian.schedule.core.update.ResumableDownload
import com.zhijian.schedule.core.update.UpdateChecker

/**
 * 更新窗口的下载选项区块：镜像加速开关、浏览器下载兜底、清理下载缓存（含确认框）。
 * 只在真的涉及安装包时出现（见 UpdateDialog.kt 的 showsDownloadOptions）。
 */

@Composable
internal fun DownloadOptions(
    ui: UpdateUiState,
    viewModel: UpdateViewModel,
    context: Context,
    onConfirmClear: () -> Unit,
) {
    val colors = LocalCourseColors.current
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
            JellySwitch(
                checked = ui.mirrorsEnabled,
                onCheckedChange = {
                    viewModel.setMirrorsEnabled(it)
                },
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

/** 清理下载缓存确认框：删除的字节数来自窗口当时展示的数量 */
@Composable
internal fun ClearCacheDialog(
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
    val reason =
        DownloadSourceSet.mirrorDisabledReason(
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
private fun openInBrowser(
    context: Context,
    info: UpdateChecker.UpdateInfo,
) {
    val directUrl = info.apkUrl ?: return
    val mirror =
        DownloadSourceSet
            .availableSources(
                directUrl = directUrl,
                digest = info.apkDigest,
                mirrors = DownloadSourceSet.MIRRORS,
            ).firstOrNull { it.id != DownloadSourceSet.DIRECT_ID }
    val target = mirror?.url ?: directUrl
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
}
