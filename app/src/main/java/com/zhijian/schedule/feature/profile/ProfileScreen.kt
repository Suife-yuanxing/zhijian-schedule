package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.R
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.RevealState
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.IslandScrollToTop
import com.zhijian.schedule.core.ui.island.islandScrollObserver
import com.zhijian.schedule.feature.update.UpdateDialog
import com.zhijian.schedule.navigation.IslandContentPadding
import com.zhijian.schedule.navigation.islandContentPadding

/**
 * 「我的」页：只保留脚手架与接线。
 *
 * 页面的三个骨架块在这里（顶部标识 / 常用入口 / 更多入口），每块偏好设置与每个入口控件
 * 都在同目录的独立文件里：
 *  - ProfileSemesterOverview.kt  学期概览大卡
 *  - ProfileEntryCards.kt        入口大卡、分组列表卡与行
 *  - ProfilePreferenceSections.kt 主题 / 深色 / 网格 / 显示 / 提醒 分组
 *  - ProfileChip.kt              偏好选项胶囊
 *  - ProfileDialogs.kt           自定义提醒与隐私政策弹窗
 *  - ProfileEntryTransition.kt   入口卡 ⇄ 二级页的转场（进入 / 返回 / 几何）
 */

/** 主色填充的三档透明度：面（大块容器）/ 小块（图标底座、标签）/ 选中态 */
internal const val ACCENT_SURFACE_ALPHA = 0.07f
internal const val ACCENT_TILE_ALPHA = 0.13f
internal const val ACCENT_SELECTED_ALPHA = 0.20f

@Composable
fun ProfileScreen(
    entryTransitions: ProfileEntryTransitions,
    reveal: RevealState,
    entranceEpoch: Int = 0,
    islandCollapse: IslandCollapseState? = null,
    scrollToTopTick: Int = 0,
    onOpenImport: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenGrades: () -> Unit = {},
    onOpenGradesFromSummary: () -> Unit = {},
    onOpenExams: () -> Unit = {},
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

    val scrollState = rememberScrollState()
    // 复点「我的」回顶
    IslandScrollToTop(state = islandCollapse, scroll = scrollState, signal = scrollToTopTick)
    Column(
        Modifier
            .fillMaxSize()
            // 底部渐隐：内容滚到浮岛下方时柔和沉入底栏，不再露出一截被切掉的文字
            .drawWithContent {
                drawContent()
                val h = islandContentPadding(islandCollapse?.animated?.value ?: 0f).toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.bg.copy(alpha = 0f), colors.bg),
                        startY = size.height - h,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - h),
                    size = Size(size.width, h),
                )
            }
            .islandScrollObserver(state = islandCollapse, scroll = scrollState)
            .verticalScroll(scrollState)
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

        // ---------- 常用：双列大卡，作为整页的主入口 ----------
        Spacer(Modifier.height(28.dp))
        StaggerIn(index = 2, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("常用")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileEntryAnchor(
                        key = ProfileEntryKeys.IMPORT,
                        transitions = entryTransitions,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        HeroEntryCard(
                            modifier = Modifier.fillMaxSize(),
                            painter = painterResource(R.drawable.ic_entry_import),
                            title = "导入课表",
                            subtitle = "教务导入 · 截图识别\n日历文件",
                            onClick = onOpenImport,
                        )
                    }
                    ProfileEntryAnchor(
                        key = ProfileEntryKeys.GRADES,
                        transitions = entryTransitions,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        HeroEntryCard(
                            modifier = Modifier.fillMaxSize(),
                            painter = painterResource(R.drawable.ic_entry_grades),
                            title = "成绩查询",
                            subtitle = "教务成绩\n加权绩点 / 平均分",
                            onClick = onOpenGrades,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GroupedEntryCard {
                    ProfileEntryAnchor(
                        key = ProfileEntryKeys.EXAMS,
                        transitions = entryTransitions,
                    ) {
                        GroupedEntryRow(
                            painter = rememberVectorPainter(Icons.Filled.DateRange),
                            title = "考试安排",
                            subtitle = ui.examPreview.ifBlank { "添加考试 · 考前自动提醒" },
                            onClick = onOpenExams,
                        )
                    }
                }
            }
        }

        // ---------- 更多：设置与说明收进同一张分组列表卡 ----------
        Spacer(Modifier.height(28.dp))
        StaggerIn(index = 3, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            Column {
                SectionTitle("更多")
                GroupedEntryCard {
                    ProfileEntryAnchor(
                        key = ProfileEntryKeys.SETTINGS,
                        transitions = entryTransitions,
                    ) {
                        GroupedEntryRow(
                            painter = rememberVectorPainter(Icons.Filled.Settings),
                            title = "高级设置",
                            subtitle = "课表信息 / 作息时间 / 日历同步 / 备份",
                            onClick = onOpenSettings,
                        )
                    }
                    GroupDivider()
                    GroupedEntryRow(
                        painter = rememberVectorPainter(Icons.Filled.Lock),
                        title = "隐私政策",
                        subtitle = "数据仅存本地 · 无广告与统计 SDK",
                        onClick = { showPrivacy = true },
                    )
                    GroupDivider()
                    GroupedEntryRow(
                        painter = rememberVectorPainter(Icons.Filled.Refresh),
                        title = "检查更新",
                        subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                        onClick = { showUpdate = true },
                    )
                    GroupDivider()
                    GroupedEntryRow(
                        painter = rememberVectorPainter(Icons.Filled.Info),
                        title = "关于",
                        subtitle = "Paper Timetable · MIT 参考实现自研",
                    )
                }
            }
        }

        // ---------- 偏好设置（见 ProfilePreferenceSections.kt） ----------
        Spacer(Modifier.height(28.dp))
        StaggerIn(index = 5, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            ProfileThemeSection(
                selectedThemeId = ui.settings.themeId,
                reveal = reveal,
                onSelectTheme = viewModel::setTheme,
            )
        }
        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 6, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            ProfileDarkModeSection(
                darkMode = ui.settings.darkMode,
                onSelect = viewModel::setDarkMode,
            )
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 7, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            ProfileGridStyleSection(
                gridStyle = ui.settings.gridStyle,
                onSelect = viewModel::setGridStyle,
            )
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 8, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            ProfileDisplaySection(
                showWeekend = ui.settings.showWeekend,
                onShowWeekendChange = viewModel::setShowWeekend,
            )
        }

        Spacer(Modifier.height(20.dp))
        StaggerIn(index = 9, stepMs = 26, maxIndex = 6, durationMs = 340, skip = skipEntrance) {
            ProfileReminderSection(
                reminderEnabled = ui.settings.reminderEnabled,
                reminderMinutes = ui.settings.reminderMinutes,
                onToggle = { enabled -> viewModel.setReminder(enabled, ui.settings.reminderMinutes) },
                onPickMinutes = { minutes -> viewModel.setReminder(true, minutes) },
                onCustomClick = { showCustomReminder = true },
            )
        }

        // 恒定留白（同 WeekGrid）：收敛进度只在 drawWithContent 里读，组合期不订阅
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
        PrivacyPolicyDialog(onDismiss = { showPrivacy = false })
    }
}

/** 分组标题：本页（常用 / 更多）与各偏好分组共用 */
@Composable
internal fun SectionTitle(text: String) {
    val colors = LocalCourseColors.current
    Text(
        text = text,
        color = colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
