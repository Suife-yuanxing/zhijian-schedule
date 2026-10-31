package com.zhijian.schedule.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.RippleLoader
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.StaggerIn

/**
 * 设置页：只保留脚手架与接线（顶栏、消息条、启动器、六个分组的编排、两个编辑弹窗）。
 *
 * 分组的归属：
 *  - SettingsSections.kt        课表信息 / 作息时间 / 备份与恢复（纯数据 + 回调）
 *  - SettingsSystemSections.kt  课前提醒 / 实时胶囊 / 日历同步（要碰系统设置与权限）
 *  - SettingsDialogs.kt         课表信息与小节时间的编辑弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui = viewModel.uiState
    val colors = LocalCourseColors.current

    // 从系统设置返回时刷新流体云放行 / 日历权限状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshRuntimeState()
    }

    var editTimetable by remember { mutableStateOf(false) }
    var editSection by remember { mutableStateOf<SectionTimeEntity?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let { viewModel.importBackup(it) } }

    val activity = LocalActivity.current
    val calendarPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            val granted = CALENDAR_PERMISSIONS.all { grants[it] == true }
            val permanentlyDenied =
                !granted &&
                    activity != null &&
                    CALENDAR_PERMISSIONS
                        .filter { grants[it] != true }
                        .all { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            viewModel.onCalendarPermissionResult(granted, permanentlyDenied)
        }

    Scaffold(
        topBar = {
            SecondaryTopBar(title = "设置", onBack = onBack)
        },
        // 真全面屏：insets 不在这里消费——滚动内容铺到手势条底下，
        // 末尾用 navigationBarsPadding 预留（见下方 Spacer）
        contentWindowInsets = WindowInsets(0.dp),
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
                enter =
                    fadeIn(tween(Motion.DUR_MEDIUM, easing = Motion.EaseOut)) +
                        expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(Motion.DUR_MEDIUM, easing = Motion.EaseOut),
                        ),
                exit =
                    fadeOut(tween(Motion.DUR_FAST)) +
                        shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(Motion.DUR_FAST),
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
                    TimetableInfoSection(
                        timetable = t,
                        onEdit = { editTimetable = true },
                    )
                }
            }

            // 作息时间
            StaggerIn(index = 1) {
                SectionTimesSection(
                    sections = ui.sections,
                    onEdit = { section -> editSection = section },
                )
            }

            // 提醒
            StaggerIn(index = 2) {
                ReminderSection(
                    exactAlarmOk = ui.exactAlarmOk,
                    onRefreshPermission = viewModel::refreshAlarmPermission,
                )
            }

            // 实时胶囊（Android 16 流体云 / Live Updates）
            StaggerIn(index = 3) {
                CapsuleSection(
                    ui = ui,
                    onToggleCapsule = viewModel::setCapsule,
                    onToggleAlarmFallback = viewModel::setCapsuleAlarmFallback,
                )
            }

            // 日历同步（方案 6.5）
            StaggerIn(index = 4) {
                CalendarSyncSection(
                    ui = ui,
                    onToggle = { checked ->
                        when {
                            !ui.calendarPermissionGranted ->
                                calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS)
                            !checked -> viewModel.setCalendarSync(false)
                            else -> viewModel.setCalendarSync(true)
                        }
                    },
                    onRequestPermission = {
                        calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS)
                    },
                    onPermissionSettingsOpened = viewModel::onCalendarPermissionSettingsOpened,
                )
            }

            // 备份与恢复
            StaggerIn(index = 5) {
                BackupSection(
                    onExport = { exportLauncher.launch("zhijian-backup.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                )
            }

            Spacer(Modifier.navigationBarsPadding().height(40.dp))
        }

        AnimatedVisibility(
            visible = ui.busy,
            enter = fadeIn(tween(Motion.DUR_MEDIUM, easing = Motion.EaseOut)),
            exit = fadeOut(tween(Motion.DUR_FAST)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.bg.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                // 备份导出 / 恢复是「握着手机等」的动作，可能持续数秒到十几秒。
                // 同上：涟漪有节拍，系统转圈没有。
                RippleLoader(
                    modifier = Modifier.size(80.dp),
                    color = colors.accent,
                    rings = 4,
                )
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
