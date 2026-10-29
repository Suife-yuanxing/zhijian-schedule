package com.zhijian.schedule.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.capsule.CapsuleReliability
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.TapTextButton

/*
 * 设置页里需要与系统打交道的三个分组：课前提醒（精确闹钟）、课程实时胶囊（流体云 / 电池优化）、
 * 写入系统日历（权限）。
 *
 * 它们各自持有「去授权 / 去开启」这类跳转：系统设置页的 Intent、权限常量与跳转方式都收在本文件，
 * 页面只负责提供启动器与 ViewModel 回调。
 */

/** 课前提醒：精确闹钟未授予时给一条「去授权」的出路 */
@Composable
internal fun ReminderSection(
    exactAlarmOk: Boolean,
    onRefreshPermission: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    SettingsCard {
        Text("课前提醒", color = colors.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (!exactAlarmOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "精确闹钟权限未授予，提醒可能延迟",
                    color = colors.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TapTextButton(onClick = {
                    onRefreshPermission()
                    requestExactAlarmPermission(context)
                }) { Text("去授权") }
            }
        } else {
            Text("精确闹钟权限已就绪", color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

/** 课程实时胶囊（Android 16 流体云 / Live Updates） */
@Composable
internal fun CapsuleSection(
    ui: SettingsUiState,
    onToggleCapsule: (Boolean) -> Unit,
    onToggleAlarmFallback: (Boolean) -> Unit,
) {
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("课程实时胶囊", color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "「即将上课」显示倒数；上课中保留秒级倒计时与进度，并在关键节点提醒剩余时间，需 Android 16+",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            JellySwitch(
                checked = ui.settings.capsuleEnabled,
                onCheckedChange = onToggleCapsule,
            )
        }
        if (ui.settings.capsuleEnabled) {
            Spacer(Modifier.height(10.dp))
            when (ui.capsulePromoteAllowed) {
                false ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "系统未放行实时活动：请在「流体云显示实时活动」中开启本应用",
                            color = colors.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TapTextButton(onClick = { openCapsuleSystemSettings(context) }) { Text("去开启") }
                    }

                true ->
                    Text(
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
                JellySwitch(
                    checked = ui.settings.capsuleAlarmFallbackEnabled,
                    onCheckedChange = onToggleAlarmFallback,
                )
            }
            TapTextButton(onClick = { CapsuleReliability.openAppDetails(context) }) {
                Text("自启动 / 耗电管理", fontSize = 12.sp)
            }
        }
    }
}

/** 日历同步（方案 6.5）：把课程写进系统日历，权限没给时提供「去授权 / 去设置」 */
@Composable
internal fun CalendarSyncSection(
    ui: SettingsUiState,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onPermissionSettingsOpened: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("写入系统日历", color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    text =
                        when {
                            !ui.calendarAvailable -> "未检测到系统日历"
                            !ui.calendarPermissionGranted ->
                                "开启后把课程写入「纸间课表」日历，负一屏日程可见"
                            else -> "把课程写入「纸间课表」日历，负一屏日程可见"
                        },
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            JellySwitch(
                checked = ui.settings.calendarSyncEnabled && ui.calendarPermissionGranted,
                enabled = ui.calendarAvailable,
                onCheckedChange = onToggle,
            )
        }
        if (ui.calendarAvailable && !ui.calendarPermissionGranted) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        when {
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
                        onPermissionSettingsOpened()
                        openCalendarPermissionSettings(context)
                    } else {
                        onRequestPermission()
                    }
                }) {
                    Text(if (ui.calendarPermissionPermanentlyDenied) "去设置" else "去授权")
                }
            }
        }
    }
}

/** 精确闹钟授权页：直接跳到本应用的对应开关 */
private fun requestExactAlarmPermission(context: Context) {
    val intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    runCatching { context.startActivity(intent) }
}

/** 系统通知设置里含「流体云显示实时活动」（原生为「实时通知」）开关 */
private fun openCapsuleSystemSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

/** 「拒绝且不再询问」后只能去系统设置的权限页手动打开 */
private fun openCalendarPermissionSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
}

/** 日历同步要的读写权限 */
internal val CALENDAR_PERMISSIONS =
    arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )
