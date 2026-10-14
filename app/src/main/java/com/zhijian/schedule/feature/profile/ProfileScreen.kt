package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.R
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.selectedPop

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onOpenImport: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenGrades: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var showPrivacy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 0) {
            Column {
                Text(
                    text = "纸间课表",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "指尖之间，一纸课表 · v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        ui.timetable?.let { t ->
            StaggerIn(index = 1) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        text = t.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${t.semester.ifBlank { "未设置学期" }} · ${t.totalWeeks} 周 · ${t.startDate} 开学",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        // ---------- 功能入口：每项一张独立卡片 ----------
        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 2) {
            Column {
                SectionTitle("常用")
                EntryCard(
                    painter = painterResource(R.drawable.ic_entry_import),
                    title = "导入课表",
                    subtitle = "教务导入 · 截图识别 · 日历文件",
                    emphasized = true,
                    onClick = onOpenImport,
                )
                Spacer(Modifier.height(10.dp))
                EntryCard(
                    painter = painterResource(R.drawable.ic_entry_grades),
                    title = "成绩查询",
                    subtitle = "教务成绩 · 加权绩点 / 平均分",
                    emphasized = true,
                    onClick = onOpenGrades,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 3) {
            Column {
                SectionTitle("设置")
                EntryCard(
                    painter = rememberVectorPainter(Icons.Filled.Settings),
                    title = "高级设置",
                    subtitle = "课表信息 / 作息时间 / 日历同步 / 备份",
                    onClick = onOpenSettings,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 4) {
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
                    painter = rememberVectorPainter(Icons.Filled.Info),
                    title = "关于",
                    subtitle = "Paper Timetable · MIT 参考实现自研",
                    onClick = {},
                )
            }
        }

        // ---------- 偏好设置 ----------
        Spacer(Modifier.height(24.dp))
        StaggerIn(index = 5) {
            Column {
                SectionTitle("主题风格")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePresets.all.forEach { preset ->
                        FilterChip(
                            selected = ui.settings.themeId == preset.id,
                            onClick = { viewModel.setTheme(preset.id) },
                            label = { Text(preset.name, fontSize = 13.sp) },
                            modifier = Modifier.selectedPop(ui.settings.themeId == preset.id),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StaggerIn(index = 6) {
            Column {
                SectionTitle("深色模式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统" to 0, "始终浅色" to 1, "始终深色" to 2).forEach { (label, mode) ->
                        FilterChip(
                            selected = ui.settings.darkMode == mode,
                            onClick = { viewModel.setDarkMode(mode) },
                            label = { Text(label, fontSize = 13.sp) },
                            modifier = Modifier.selectedPop(ui.settings.darkMode == mode),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 7) {
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
        StaggerIn(index = 8) {
            Column {
                SectionTitle("上课提醒")
                SettingSwitchRow(
                    title = "课前提醒",
                    subtitle = "提前 ${ui.settings.reminderMinutes} 分钟提醒（精确闹钟），实时胶囊同步倒计时",
                    checked = ui.settings.reminderEnabled,
                    onCheckedChange = { viewModel.setReminder(it, ui.settings.reminderMinutes) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        val active = ui.settings.reminderMinutes == m && ui.settings.reminderEnabled
                        FilterChip(
                            selected = active,
                            onClick = { viewModel.setReminder(true, m) },
                            label = { Text("$m 分钟", fontSize = 12.sp) },
                            modifier = Modifier.selectedPop(active),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
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
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("我知道了") } },
        )
    }
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            .pressScale(pressSource, pressedScale = 0.98f)
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
