package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.R
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.theme.GridStyles
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.selectedPop
import com.zhijian.schedule.feature.update.UpdateDialog
import com.zhijian.schedule.navigation.IslandContentPadding
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    entryTransitions: ProfileEntryTransitions,
    entranceEpoch: Int = 0,
    onOpenImport: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenGrades: () -> Unit = {},
    onOpenGradesFromSummary: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var showCustomReminder by remember { mutableStateOf(false) }
    // 每次从其他 tab 切到「我的」都重播级联；从二级页返回时直接落位，
    // 保证入口卡锚点第一帧就可见（epoch 未变化 → skip）。
    var animatedEpoch by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    val playEntrance = animatedEpoch != entranceEpoch
    val skipEntrance = !playEntrance
    LaunchedEffect(entranceEpoch) {
        if (animatedEpoch != entranceEpoch) animatedEpoch = entranceEpoch
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 0, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                Text(
                    text = "纸间课表",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "指尖之间，一纸课表 · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        ui.timetable?.let { t ->
            StaggerIn(
                index = 1,
                stepMs = 26,
                maxIndex = 6,
                durationMs = 340,
                skip = skipEntrance,
            ) {
                SemesterOverviewCard(
                    timetable = t,
                    week = ui.week,
                    courseCount = ui.courseCount,
                    totalCredits = ui.totalCredits,
                    weightedGpa = ui.weightedGpa,
                    entryTransitions = entryTransitions,
                    onOpenGrades = onOpenGradesFromSummary,
                )
            }
        }

        // ---------- 功能入口：每项一张独立卡片 ----------
        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 2, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("常用")
                ProfileEntryAnchor(
                    key = ProfileEntryKeys.IMPORT,
                    transitions = entryTransitions,
                ) {
                    EntryCard(
                        painter = painterResource(R.drawable.ic_entry_import),
                        title = "导入课表",
                        subtitle = "教务导入 · 截图识别 · 日历文件",
                        emphasized = true,
                        onClick = onOpenImport,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ProfileEntryAnchor(
                    key = ProfileEntryKeys.GRADES,
                    transitions = entryTransitions,
                ) {
                    EntryCard(
                        painter = painterResource(R.drawable.ic_entry_grades),
                        title = "成绩查询",
                        subtitle = "教务成绩 · 加权绩点 / 平均分",
                        emphasized = true,
                        onClick = onOpenGrades,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 3, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("设置")
                ProfileEntryAnchor(
                    key = ProfileEntryKeys.SETTINGS,
                    transitions = entryTransitions,
                ) {
                    EntryCard(
                        painter = rememberVectorPainter(Icons.Filled.Settings),
                        title = "高级设置",
                        subtitle = "课表信息 / 作息时间 / 日历同步 / 备份",
                        onClick = onOpenSettings,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 4, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("其他")
                EntryCard(
                    painter = rememberVectorPainter(Icons.Filled.Lock),
                    title = "隐私政策",
                    subtitle = "数据仅存本地 · 无广告与统计 SDK",
                    onClick = { showPrivacy = true },
                )
                Spacer(Modifier.height(10.dp))
                EntryCard(
                    painter = rememberVectorPainter(Icons.Filled.Refresh),
                    title = "检查更新",
                    subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                    onClick = { showUpdate = true },
                )
                Spacer(Modifier.height(10.dp))
                EntryCard(
                    painter = rememberVectorPainter(Icons.Filled.Info),
                    title = "关于",
                    subtitle = "Paper Timetable · MIT 参考实现自研",
                    onClick = {},
                )
            }
        }

        // ---------- 偏好设置 ----------
        Spacer(Modifier.height(24.dp))
        StaggerIn(index = 5, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("主题风格")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePresets.all.forEach { preset ->
                        ProfileChip(
                            label = preset.name,
                            selected = ui.settings.themeId == preset.id,
                            onClick = { viewModel.setTheme(preset.id) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StaggerIn(index = 6, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("深色模式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统" to 0, "始终浅色" to 1, "始终深色" to 2).forEach { (label, mode) ->
                        ProfileChip(
                            label = label,
                            selected = ui.settings.darkMode == mode,
                            onClick = { viewModel.setDarkMode(mode) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        StaggerIn(index = 7, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("网格风格")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridStyles.all.forEach { (id, label) ->
                        ProfileChip(
                            label = label,
                            selected = ui.settings.gridStyle == id,
                            onClick = { viewModel.setGridStyle(id) },
                        )
                    }
                }
                Text(
                    text = when (ui.settings.gridStyle) {
                        GridStyles.BLOCK -> "去掉全部网格线：只留三段时段底纹与色块自身分层，适合信息密度高的课表"
                        else -> "上午 / 下午 / 晚上三段标题 + 间距分层，段内只留一条 hairline，课块用同色系容器"
                    },
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 8, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("课表显示")
                SettingSwitchRow(
                    title = "显示周末",
                    subtitle = "在周视图中显示周六、周日两列",
                    checked = ui.settings.showWeekend,
                    onCheckedChange = viewModel::setShowWeekend,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 9, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("上课提醒")
                SettingSwitchRow(
                    title = "课前提醒",
                    subtitle = "提前 ${ui.settings.reminderMinutes} 分钟提醒（精确闹钟），实时胶囊同步倒计时",
                    checked = ui.settings.reminderEnabled,
                    onCheckedChange = { viewModel.setReminder(it, ui.settings.reminderMinutes) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(10, 30)
                    presets.forEach { m ->
                        ProfileChip(
                            label = "$m 分钟",
                            selected = ui.settings.reminderEnabled && ui.settings.reminderMinutes == m,
                            onClick = { viewModel.setReminder(true, m) },
                        )
                    }
                    ProfileChip(
                        label = "自定义",
                        selected = ui.settings.reminderEnabled && ui.settings.reminderMinutes !in presets,
                        onClick = { showCustomReminder = true },
                    )
                }
            }
        }

        Spacer(Modifier.height(IslandContentPadding))
    }

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false })
    }

    if (showCustomReminder) {
        CustomReminderDialog(
            initialMinutes = ui.settings.reminderMinutes,
            onDismiss = { showCustomReminder = false },
            onConfirm = { minutes ->
                showCustomReminder = false
                viewModel.setReminder(true, minutes)
            },
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隐私政策（摘要）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "1. 所有数据默认仅存储在你的手机本地（Room 数据库与本地设置）。\n\n" +
                            "2. 教务账号密码仅在你手机与学校服务器之间传递，本应用不收集、不上传、不记录账号密码；「记住密码」为可选功能且默认关闭。\n\n" +
                            "3. 网络访问仅用于：教务课表抓取（直连学校）、应用内检查更新（GitHub）。\n\n" +
                            "4. 你可以随时导出全部数据（JSON），卸载应用即删除全部本地数据。\n\n" +
                            "5. 本应用不接入任何第三方广告 SDK 与统计 SDK。",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = { TapTextButton(onClick = { showPrivacy = false }) { Text("我知道了") } },
        )
    }
}

/** 学期概览：周次进度 + 课程数 + 学分/绩点，GPA 区可直接进入成绩页 */
@Composable
private fun SemesterOverviewCard(
    timetable: TimetableEntity,
    week: Int?,
    courseCount: Int,
    totalCredits: Double,
    weightedGpa: Double?,
    entryTransitions: ProfileEntryTransitions,
    onOpenGrades: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val shape = RoundedCornerShape(18.dp)
    val fraction = if (week != null && timetable.totalWeeks > 0) {
        week.toFloat() / timetable.totalWeeks
    } else 0f
    val stats = "${timetable.startDate} 开学 · $courseCount 门课程"
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timetable.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timetable.semester.ifBlank { "未设置学期" },
                        color = colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(colors.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = week?.let { "第 $it 周" } ?: "学期外",
                        color = colors.accent,
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "共 ${timetable.totalWeeks} 周",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stats,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            ProfileEntryAnchor(
                key = ProfileEntryKeys.GRADES_GPA,
                transitions = entryTransitions,
            ) {
                GpaTile(
                    weightedGpa = weightedGpa,
                    totalCredits = totalCredits,
                    onClick = onOpenGrades,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = colors.accent,
            trackColor = colors.border.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(7.dp))
        Row {
            Text("学期进度", color = colors.textMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = week?.let { "第 $it / ${timetable.totalWeeks} 周" } ?: "不在学期内",
                color = colors.textMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GpaTile(weightedGpa: Double?, totalCredits: Double, onClick: () -> Unit) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .pressScale(source, pressedScale = 0.94f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent.copy(alpha = 0.10f))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = {
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("加权绩点", color = colors.textMuted, fontSize = 9.sp)
            Text(" ›", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = weightedGpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            color = colors.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (totalCredits > 0) {
            Text(
                text = "${String.format(Locale.US, "%.1f", totalCredits)} 学分",
                color = colors.textMuted,
                fontSize = 9.sp,
            )
        }
    }
}

/** 偏好选项：选中=强调色描边 + 浅色底 + 加粗；只保留按压回弹，选中不额外震动 */
@Composable
private fun ProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    FilterChip(
        selected = selected,
        onClick = {
            onClick()
        },
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        interactionSource = source,
        colors = profileChipColors(),
        border = profileChipBorder(selected),
        modifier = Modifier
            .pressScale(source, pressedScale = 0.94f)
            .selectedPop(selected),
    )
}

@Composable
private fun profileChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = LocalCourseColors.current.textMuted,
    selectedContainerColor = LocalCourseColors.current.accent.copy(alpha = 0.20f),
    selectedLabelColor = LocalCourseColors.current.text,
)

@Composable
private fun profileChipBorder(selected: Boolean): BorderStroke {
    val colors = LocalCourseColors.current
    return BorderStroke(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) colors.accent else colors.border,
    )
}

@Composable
private fun CustomReminderDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember(initialMinutes) { mutableStateOf(initialMinutes.toString()) }
    val minutes = text.toIntOrNull()
    val valid = minutes != null && minutes in 1..120
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义课前提醒") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    if (input.length <= 3 && input.all(Char::isDigit)) text = input
                },
                label = { Text("提前分钟数") },
                supportingText = { Text("输入 1–120 分钟") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TapTextButton(
                enabled = valid,
                hapticType = HapticFeedbackType.Confirm,
                onClick = { minutes?.let(onConfirm) },
            ) { Text("保存") }
        },
        dismissButton = {
            TapTextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalCourseColors.current
    Text(
        text = text,
        color = colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 15.sp)
            Text(subtitle, color = colors.textMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                onCheckedChange(it)
            },
        )
    }
}

/** 独立入口卡：40dp 图标底座（常用=主色、其余=中性）+ 标题 + 说明 + 箭头 */
@Composable
private fun EntryCard(
    painter: Painter,
    title: String,
    subtitle: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val tint = if (emphasized) colors.accent else colors.textMuted
    Row(
        Modifier
            .fillMaxWidth()
            .buttonFeedback(pressSource, pressedScale = 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (emphasized) colors.accent.copy(alpha = 0.13f) else colors.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(text = subtitle, fontSize = 12.sp, color = colors.textMuted)
        }
        Text(
            text = "›",
            color = colors.textMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
