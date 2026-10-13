package com.zhijian.schedule.feature.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.model.ImportChange
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.pressScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImporterScreen(
    onBack: () -> Unit,
    onJwImport: () -> Unit,
    viewModel: ImporterViewModel = hiltViewModel(),
) {
    val colors = LocalCourseColors.current
    val state = viewModel.state
    var showPasteDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importFromUri(it) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFromImages(uris)
    }

    Scaffold(
        topBar = {
            SecondaryTopBar(title = "导入课表", onBack = onBack)
        },
        containerColor = colors.bg,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    val forward = importStateOrder(targetState) >= importStateOrder(initialState)
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(animationSpec = Motion.SlideSpring) { dir * it / 4 } +
                        fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut))) togetherWith
                        (slideOutHorizontally(animationSpec = Motion.SlideSpring) { -dir * it / 4 } +
                            fadeOut(tween(durationMillis = Motion.DurFast)))
                },
                label = "importFlow",
            ) { s ->
                when (s) {
                    is ImportUiState.Idle -> MethodList(
                        onJwImport = onJwImport,
                        onPickFile = {
                            filePicker.launch(
                                arrayOf(
                                    "text/*",
                                    "text/calendar",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-excel",
                                    "application/json",
                                    "*/*",
                                )
                            )
                        },
                        onPaste = { showPasteDialog = true },
                        onPickImages = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    is ImportUiState.Parsing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = colors.accent)
                            if (viewModel.parsingNote.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(viewModel.parsingNote, color = colors.textMuted, fontSize = 13.sp)
                            }
                        }
                    }
                    is ImportUiState.OcrReview -> viewModel.ocrReview?.let { review ->
                        OcrReviewContent(
                            review = review,
                            onUpdate = { index, draft -> viewModel.updateOcrDraft(index, draft) },
                            onRemove = { index -> viewModel.removeOcrDraft(index) },
                            onConfirm = { viewModel.confirmOcrReview() },
                            onBack = { viewModel.reset() },
                        )
                    }
                    is ImportUiState.Preview -> PreviewContent(
                        changes = s.preview.changes,
                        summary = s.preview.summary(),
                        hasConflict = s.preview.conflictCount > 0,
                        applyConflicts = viewModel.applyConflicts,
                        onToggleConflicts = { viewModel.updateApplyConflicts(it) },
                        onConfirm = { viewModel.apply() },
                        onCancel = { viewModel.reset() },
                    )
                    is ImportUiState.Done -> DoneContent(s.summary, onBack)
                    is ImportUiState.Error -> ErrorContent(s.message, onRetry = { viewModel.reset() })
                }
            }
        }
    }

    if (showPasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴课表文本") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("支持：分享口令 / JSON / HTML 表格 / CSV 文本") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasteDialog = false
                        viewModel.importFromText(text)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("解析") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MethodList(
    onJwImport: () -> Unit,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    onPickImages: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MethodCard(
            icon = Icons.Filled.Lock,
            title = "教务系统导入",
            subtitle = "直连学校教务（正方等），账号仅在你与学校之间传递",
            highlight = true,
            onClick = onJwImport,
        )
        MethodCard(
            icon = Icons.Filled.List,
            title = "Excel / CSV 文件",
            subtitle = "表头：课程名 / 星期 / 开始节 / 结束节 / 老师 / 地点 / 周数",
            onClick = onPickFile,
        )
        MethodCard(
            icon = Icons.Filled.Edit,
            title = "粘贴课表口令 / 文本",
            subtitle = "分享口令、JSON、从 Excel 复制的表格均可",
            onClick = onPaste,
        )
        MethodCard(
            icon = Icons.Filled.Search,
            title = "截图识别",
            subtitle = "选课表截图自动识别成课程，支持多张",
            onClick = onPickImages,
        )
        MethodCard(
            icon = Icons.Filled.DateRange,
            title = "日历 (.ics) 文件",
            subtitle = "导入其他日历 App 导出的 .ics 课表文件",
            onClick = onPickFile,
        )
    }
}

@Composable
private fun MethodCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(pressSource, pressedScale = 0.97f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlight) colors.accent else colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = colors.textMuted,
            )
        }
        if (highlight) {
            Text("推荐", color = colors.accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewContent(
    changes: List<ImportChange>,
    summary: String,
    hasConflict: Boolean,
    applyConflicts: Boolean,
    onToggleConflicts: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("重新选择") }
        }
        HorizontalDivider(color = colors.border)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(changes) { idx, change ->
                StaggerIn(index = idx) {
                    ChangeRow(change)
                }
            }
        }
        if (hasConflict) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("覆盖手动编辑的课程", color = colors.text, fontSize = 14.sp)
                    Text(
                        "默认跳过你手动改过的课程（冲突项）",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(checked = applyConflicts, onCheckedChange = onToggleConflicts)
            }
        }
        val confirmSource = remember { MutableInteractionSource() }
        Button(
            onClick = onConfirm,
            interactionSource = confirmSource,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .pressScale(confirmSource, pressedScale = 0.97f),
        ) { Text("确认导入") }
    }
}

@Composable
private fun ChangeRow(change: ImportChange) {
    val colors = LocalCourseColors.current
    val (label, badgeColor) = when (change.kind) {
        ImportChangeKind.ADDED -> "新增" to Color(0xFF2BB673)
        ImportChangeKind.CHANGED -> "变更" to Color(0xFFF59E0B)
        ImportChangeKind.REMOVED -> "取消" to Color(0xFFEF4444)
        ImportChangeKind.UNCHANGED -> "无变化" to colors.textMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(label, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = change.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            if (change.detail.isNotBlank()) {
                Text(change.detail, fontSize = 12.sp, color = colors.textMuted)
            }
            if (change.conflict) {
                Text(
                    "你手动编辑过，默认保留本地版本",
                    fontSize = 11.sp,
                    color = Color(0xFFEF4444),
                )
            }
        }
    }
}

@Composable
private fun DoneContent(summary: String, onDone: () -> Unit) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val iconScale by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = Motion.PopSpring,
        label = "doneIcon",
    )
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2BB673),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    alpha = iconScale.coerceIn(0f, 1f)
                },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "导入完成",
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(summary, color = colors.textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        val doneSource = remember { MutableInteractionSource() }
        Button(
            onClick = onDone,
            interactionSource = doneSource,
            modifier = Modifier.pressScale(doneSource, pressedScale = 0.96f),
        ) { Text("返回课表") }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    val colors = LocalCourseColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = colors.text,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("重新选择") }
    }
}

/** 导入流程的推进方向：前进从右侧滑入，回退（重新选择 / 重试）从左侧滑入 */
private fun importStateOrder(state: ImportUiState): Int = when (state) {
    is ImportUiState.Idle -> 0
    is ImportUiState.Parsing -> 1
    is ImportUiState.Error -> 1
    is ImportUiState.OcrReview -> 2
    is ImportUiState.Preview -> 3
    is ImportUiState.Done -> 4
}
