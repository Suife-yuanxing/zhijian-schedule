package com.zhijian.schedule.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zhijian.schedule.capsule.CapsuleReliability
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.theme.LocalCourseColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui = viewModel.uiState
    val colors = LocalCourseColors.current
    val context = LocalContext.current

    // 从系统设置返回时刷新流体云放行 / 日历权限状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshRuntimeState()
    }

    var editTimetable by remember { mutableStateOf(false) }
    var editSection by remember { mutableStateOf<SectionTimeEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    val activity = LocalActivity.current
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = CALENDAR_PERMISSIONS.all { grants[it] == true }
        val permanentlyDenied = !granted && activity != null &&
            CALENDAR_PERMISSIONS
                .filter { grants[it] != true }
                .all { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        viewModel.onCalendarPermissionResult(granted, permanentlyDenied)
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
                            TapTextButton(onClick = { editTimetable = true }) { Text("编辑") }
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
                                .buttonFeedback(pressSource, pressedScale = 0.98f)
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
                            TapTextButton(onClick = {
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
                                text = "「即将上课」显示倒数；上课中保留秒级倒计时与进度，并在关键节点提醒剩余时间，需 Android 16+",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        SettingsSwitch(
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
                                TapTextButton(onClick = { openCapsuleSystemSettings(context) }) { Text("去开启") }
                            }

                            true -> Text(
                                text = "已放行实时活动，胶囊会显示为流体云",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )

                            null -> Unit
                        }
                        Spacer(Modifier.height(6.dp))
                        if (!ui.batteryOptimizationIgnored) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "后台运行未放行：系统可能冻结胶囊状态",
                                    color = colors.accent,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TapTextButton(onClick = {
                                    CapsuleReliability.requestIgnoreBatteryOptimization(context)
                                }) { Text("去允许") }
                            }
                        } else {
                            Text(
                                text = "后台运行已放行，状态切换更可靠",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("系统闹钟兜底", color = colors.text, fontSize = 13.sp)
                                Text(
                                    "临近切换用系统闹钟唤醒，状态栏会短暂显示闹钟图标",
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            SettingsSwitch(
                                checked = ui.settings.capsuleAlarmFallbackEnabled,
                                onCheckedChange = { viewModel.setCapsuleAlarmFallback(it) },
                            )
                        }
                        TapTextButton(onClick = { CapsuleReliability.openAppDetails(context) }) {
                            Text("自启动 / 耗电管理", fontSize = 12.sp)
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
                                text = when {
                                    !ui.calendarAvailable -> "未检测到系统日历"
                                    !ui.calendarPermissionGranted ->
                                        "开启后把课程写入「纸间课表」日历，负一屏日程可见"
                                    else -> "把课程写入「纸间课表」日历，负一屏日程可见"
                                },
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        SettingsSwitch(
                            checked = ui.settings.calendarSyncEnabled && ui.calendarPermissionGranted,
                            enabled = ui.calendarAvailable,
                            onCheckedChange = { checked ->
                                when {
                                    !ui.calendarPermissionGranted ->
                                        calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS)
                                    !checked -> viewModel.setCalendarSync(false)
                                    else -> viewModel.setCalendarSync(true)
                                }
                            },
                        )
                    }
                    if (ui.calendarAvailable && !ui.calendarPermissionGranted) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    ui.calendarPermissionPermanentlyDenied ->
                                        "日历权限已被拒绝，请到系统设置中允许"
                                    ui.calendarPermissionDenied ->
                                        "未授予日历权限，无法写入系统日历"
                                    else -> "需要读取和写入日历权限"
                                },
                                color = colors.accent,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TapTextButton(onClick = {
                                if (ui.calendarPermissionPermanentlyDenied) {
                                    viewModel.onCalendarPermissionSettingsOpened()
                                    openCalendarPermissionSettings(context)
                                } else {
                                    calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS)
                                }
                            }) {
                                Text(if (ui.calendarPermissionPermanentlyDenied) "去设置" else "去授权")
                            }
                        }
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
                            modifier = Modifier.buttonFeedback(
                                exportSource,
                                pressedScale = 0.96f,
                            ),
                        ) {
                            Text("导出备份")
                        }
                        TapTextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Text("导入恢复")
                        }
                    }
                }
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
                            onClick = {
                                weeks = w
                            },
                            label = { Text("$w", fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TapTextButton(
                hapticType = HapticFeedbackType.Confirm,
                onClick = { onSave(name, semester, startDate, weeks) },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
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
        confirmButton = {
            TapTextButton(
                hapticType = HapticFeedbackType.Confirm,
                onClick = { onSave(start, end) },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = {
            onCheckedChange(it)
        },
    )
}

/** 系统通知设置里含「流体云显示实时活动」（原生为「实时通知」）开关 */
private fun openCapsuleSystemSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

/** 「拒绝且不再询问」后只能去系统设置的权限页手动打开 */
private fun openCalendarPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
