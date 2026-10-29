package com.zhijian.schedule.feature.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.update.DownloadState
import com.zhijian.schedule.core.update.ResumableDownload
import com.zhijian.schedule.core.update.UpdateChecker

/*
 * 更新窗口的状态区块：把 [DownloadState] / 检查结果渲染成一张状态卡片。
 * 一屏只出现一张卡片（[UpdateStatusCard] 负责分发），其余卡片互斥。
 */

/** 状态分发：按「下载状态优先于检查状态」的顺序挑一张卡片 */
@Composable
internal fun UpdateStatusCard(ui: UpdateUiState) {
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

/** 卡片壳：圆角方块 + 细描边，所有状态卡片共用 */
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
    val fraction =
        state.totalBytes
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
    val fraction =
        state.totalBytes
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
private fun FailedCard(
    state: DownloadState.Failed,
    mirrorsEnabled: Boolean,
) {
    val hint =
        if (!mirrorsEnabled && isSourceFailure(state.reason)) {
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
private fun ProblemCard(
    title: String,
    detail: String,
    hint: String?,
) {
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

/** 动作反馈条：Viewmodel 给出的一句话提示（例如「已清理下载缓存」） */
@Composable
internal fun MessageBanner(message: String) {
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

/** 确定进度条：320ms 补间，跟得上 250ms 一次的状态推送 */
@Composable
private fun ProgressBar(fraction: Float) {
    val colors = LocalCourseColors.current
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320, easing = Motion.EaseOut),
        label = "updateProgress",
    )
    Box(
        modifier =
            Modifier
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

/** 不确定进度条：响应没有长度信息时用（总长未知） */
@Composable
private fun IndeterminateBar() {
    val colors = LocalCourseColors.current
    LinearProgressIndicator(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        color = colors.accent,
        trackColor = colors.border,
    )
}

private fun runningDetail(state: DownloadState.Running): String {
    // 刚点下载时还在探测/连接源，进度和速度都是 0：给一句明确的「在干活」而不是干巴巴的 0 B
    if (state.downloadedBytes <= 0L && state.bytesPerSecond <= 0L) return "正在连接下载源…"
    val size =
        state.totalBytes?.let {
            "${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}"
        } ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    val speed =
        state.bytesPerSecond
            .takeIf { it > 0L }
            ?.let { " · ${ResumableDownload.formatBytes(it)}/s" }
            .orEmpty()
    val eta = ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty()
    val source = state.sourceLabel?.let { " · 经 $it" }.orEmpty()
    return size + speed + eta + source
}
