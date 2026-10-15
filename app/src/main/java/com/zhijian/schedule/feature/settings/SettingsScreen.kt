package com.zhijian.schedule.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.update.DownloadState
import com.zhijian.schedule.update.ResumableDownload
import com.zhijian.schedule.update.UpdateChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui = viewModel.uiState
    val colors = LocalCourseColors.current
    val context = LocalContext.current

    // 从系统设置返回时刷新流体云放行状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCapsulePromote()
        viewModel.refreshInstallPermission()
    }

    var editTimetable by remember { mutableStateOf(false) }
    var editSection by remember { mutableStateOf<SectionTimeEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.setCalendarSync(true)
        } else {
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(title = "设置", onBack = onBack)
        },
        containerColor = colors.bg,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            AnimatedVisibility(
                visible = ui.message != null,
                enter = fadeIn(tween(Motion.DurMedium, easing = Motion.EaseOut)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(Motion.DurMedium, easing = Motion.EaseOut),
                    ),
                exit = fadeOut(tween(Motion.DurFast)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(Motion.DurFast),
                    ),
            ) {
                Text(
                    text = ui.message.orEmpty(),
                    color = colors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            // 课表信息
            ui.timetable?.let { t ->
                StaggerIn(index = 0) {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "课表信息",
                                color = colors.text,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editTimetable = true }) { Text("编辑") }
                        }
                        InfoRow("名称", t.name)
                        InfoRow("学期", t.semester.ifBlank { "未设置" })
                        InfoRow("开学日期", t.startDate)
                        InfoRow("总周数", "${t.totalWeeks} 周")
                    }
                }
            }

            // 作息时间
            StaggerIn(index = 1) {
                Card {
                    Text("作息时间", color = colors.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    ui.sections.forEach { section ->
                        val pressSource = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressScale(pressSource, pressedScale = 0.98f)
                                .clickable(
                                    interactionSource = pressSource,
                                    indication = LocalIndication.current,
                                ) { editSection = section }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "小节 ${section.sectionNo}",
                                color = colors.text,
                                fontSize = 14.sp,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(
                                "${section.startTime} - ${section.endTime}",
                                color = colors.textMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // 提醒
            StaggerIn(index = 2) {
                Card {
                    Text("课前提醒", color = colors.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (!ui.exactAlarmOk) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "精确闹钟权限未授予，提醒可能延迟",
                                color = colors.accent,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                viewModel.refreshAlarmPermission()
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                runCatching { context.startActivity(intent) }
                            }) { Text("去授权") }
                        }
                    } else {
                        Text("精确闹钟权限已就绪", color = colors.textMuted, fontSize = 12.sp)
                    }
                }
            }

            // 实时胶囊（Android 16 流体云 / Live Updates）
            StaggerIn(index = 3) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("课程实时胶囊", color = colors.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "「即将上课 / 上课中」会显示为状态栏流体云（课前按提醒设置倒数），需 Android 16+",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = ui.settings.capsuleEnabled,
                            onCheckedChange = { viewModel.setCapsule(it) },
                        )
                    }
                    if (ui.settings.capsuleEnabled) {
                        Spacer(Modifier.height(10.dp))
                        when (ui.capsulePromoteAllowed) {
                            false -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "系统未放行实时活动：请在「流体云显示实时活动」中开启本应用",
                                    color = colors.accent,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { openCapsuleSystemSettings(context) }) { Text("去开启") }
                            }

                            true -> Text(
                                text = "已放行实时活动，胶囊会显示为流体云",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )

                            null -> Unit
                        }
                    }
                }
            }

            // 日历同步（方案 6.5）
            StaggerIn(index = 4) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("写入系统日历", color = colors.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (ui.calendarAvailable) {
                                    "把课程写入「纸间课表」日历，负一屏日程可见"
                                } else {
                                    "未检测到系统日历"
                                },
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = ui.settings.calendarSyncEnabled,
                            enabled = ui.calendarAvailable,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    calendarPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.READ_CALENDAR,
                                            android.Manifest.permission.WRITE_CALENDAR,
                                        )
                                    )
                                } else {
                                    viewModel.setCalendarSync(false)
                                }
                            },
                        )
                    }
                }
            }

            // 备份与恢复
            StaggerIn(index = 5) {
                Card {
                    Text("备份与恢复", color = colors.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "导出为 JSON 文件，可换机恢复（本地优先，方案 3.1）",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val exportSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = { exportLauncher.launch("zhijian-backup.json") },
                            interactionSource = exportSource,
                            modifier = Modifier.pressScale(exportSource, pressedScale = 0.96f),
                        ) {
                            Text("导出备份")
                        }
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Text("导入恢复")
                        }
                    }
                }
            }

            // 检查更新（方案 1.4）
            StaggerIn(index = 6) {
                UpdateSection(ui = ui, viewModel = viewModel, context = context)
            }

            Spacer(Modifier.height(40.dp))
        }

        AnimatedVisibility(
            visible = ui.busy,
            enter = fadeIn(tween(Motion.DurMedium, easing = Motion.EaseOut)),
            exit = fadeOut(tween(Motion.DurFast)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.bg.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accent)
            }
        }
    }

    if (editTimetable) {
        ui.timetable?.let { t ->
            TimetableEditDialog(
                initialName = t.name,
                initialSemester = t.semester,
                initialStartDate = t.startDate,
                initialWeeks = t.totalWeeks,
                onDismiss = { editTimetable = false },
                onSave = { name, semester, date, weeks ->
                    editTimetable = false
                    viewModel.saveTimetable(name, semester, date, weeks)
                },
            )
        }
    }

    editSection?.let { section ->
        SectionEditDialog(
            section = section,
            onDismiss = { editSection = null },
            onSave = { s, e ->
                editSection = null
                viewModel.saveSectionTime(section, s, e)
            },
        )
    }
}

/** 更新卡片：未检查/检查中/已最新/检查失败/有新版本/无安装包/下载中/已取消/下载失败/待安装/校验失败 */
@Composable
private fun UpdateSection(
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
    context: android.content.Context,
) {
    val colors = LocalCourseColors.current
    var confirmClear by remember { mutableStateOf(false) }

    Card {
        Text("检查更新", color = colors.text, fontWeight = FontWeight.SemiBold)
        UpdateStatusLine(ui)
        Spacer(Modifier.height(8.dp))

        val download = ui.download
        if (download is DownloadState.Running) {
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
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (download) {
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

            val pageUrl = ui.updateInfo?.pageUrl
            if (!pageUrl.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }
                }) { Text("打开发布页") }
            }
        }

        // 下载中隐藏：分片正被写入，而且此时 updateCacheBytes 还是上一次终态的旧值
        if (ui.updateCacheBytes > 0L && download !is DownloadState.Running) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmClear = true }) {
                Text(
                    "清理下载缓存（${ResumableDownload.formatBytes(ui.updateCacheBytes)}）",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }

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

/** 卡片的状态行：下载状态下优先显示下载信息，否则显示检查结果 */
@Composable
private fun UpdateStatusLine(ui: SettingsUiState) {
    val colors = LocalCourseColors.current
    val download = ui.download
    val (text, emphasized) = when {
        download is DownloadState.Running -> runningLine(download) to true
        download is DownloadState.Paused ->
            "已取消 · 已保留 ${ResumableDownload.formatBytes(download.downloadedBytes)} 进度" to false
        download is DownloadState.Ready -> "下载完成，待安装（${ResumableDownload.formatBytes(download.sizeBytes)}）" to true
        download is DownloadState.Failed -> download.reason to false
        ui.updateError != null -> "检查更新失败：${ui.updateError}" to false
        ui.updateInfo != null -> versionLine(ui.updateInfo) to true
        ui.updateChecked -> "已是最新版本 v${BuildConfig.VERSION_NAME}" to false
        else -> "通过 GitHub Releases 分发（当前 v${BuildConfig.VERSION_NAME}）" to false
    }
    Text(text, color = if (emphasized) colors.text else colors.textMuted, fontSize = 12.sp)
}

private fun runningLine(state: DownloadState.Running): String {
    val head = state.totalBytes
        ?.let { "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}" }
        ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    return head + (ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty())
}

private fun versionLine(info: UpdateChecker.UpdateInfo): String = if (info.apkUrl == null) {
    "发现新版本 v${info.version}（该版本未提供安装包）"
} else {
    "发现新版本 v${info.version}" +
        (info.apkSize?.takeIf { it > 0 }?.let { " · 大小 ${ResumableDownload.formatBytes(it)}" } ?: "")
}

private fun checkLabel(ui: SettingsUiState): String = when {
    ui.busy -> "检查中…"
    ui.updateChecked -> "重新检查"
    else -> "检查更新"
}

/** 卡片里的主操作按钮：统一的按压缩放反馈 */
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

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalCourseColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = colors.text, fontSize = 13.sp)
    }
}

@Composable
private fun TimetableEditDialog(
    initialName: String,
    initialSemester: String,
    initialStartDate: String,
    initialWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var semester by remember { mutableStateOf(initialSemester) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var weeks by remember { mutableStateOf(initialWeeks) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表信息") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课表名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = semester,
                    onValueChange = { semester = it },
                    label = { Text("学期（如 2026 秋季）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("第 1 周周一（yyyy-MM-dd）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text("总周数", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 18, 20, 22).forEach { w ->
                        FilterChip(
                            selected = weeks == w,
                            onClick = { weeks = w },
                            label = { Text("$w", fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, semester, startDate, weeks) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionEditDialog(
    section: SectionTimeEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var start by remember { mutableStateOf(section.startTime) }
    var end by remember { mutableStateOf(section.endTime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小节 ${section.sectionNo} 时间") },
        text = {
            Column {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始（HH:mm）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("结束（HH:mm）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(start, end) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 系统通知设置里含「流体云显示实时活动」（原生为「实时通知」）开关 */
private fun openCapsuleSystemSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
