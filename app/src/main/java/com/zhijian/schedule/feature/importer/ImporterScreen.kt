package com.zhijian.schedule.feature.importer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.metal.MetalFields
import com.zhijian.schedule.feature.importer.qr.QrScanContent
import com.zhijian.schedule.feature.importer.qr.QrShareContent

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

    // 系统返回（手势/按键）与顶栏返回保持一致：扫码/分享是页内流程态，先回方法列表
    BackHandler(enabled = state is ImportUiState.QrScan || state is ImportUiState.QrShare) {
        viewModel.reset()
    }

    val filePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { viewModel.importFromUri(it) }
        }

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
        ) { uris ->
            if (uris.isNotEmpty()) viewModel.importFromImages(uris)
        }

    Scaffold(
        topBar = {
            SecondaryTopBar(
                title = topTitle(state),
                onBack = {
                    // 扫码/分享是导入页内的流程态：返回先回方法列表，而不是直接退出页面
                    if (state is ImportUiState.QrScan || state is ImportUiState.QrShare) {
                        viewModel.reset()
                    } else {
                        onBack()
                    }
                },
            )
        },
        containerColor = colors.bg,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    // 二维码互传的扫码/分享页硬切：相机预览是独立合成层，无法像普通
                    // Compose 内容一样滑出/淡出；大尺寸二维码原地淡出也会叠成残影。
                    if (initialState is ImportUiState.QrScan ||
                        targetState is ImportUiState.QrScan ||
                        initialState is ImportUiState.QrShare ||
                        targetState is ImportUiState.QrShare
                    ) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val forward = importStateOrder(targetState) >= importStateOrder(initialState)
                        val dir = if (forward) 1 else -1
                        (
                            slideInHorizontally(animationSpec = Motion.SlideSpring) { dir * it / 4 } +
                                fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut))
                        ) togetherWith
                            (
                                slideOutHorizontally(animationSpec = Motion.SlideSpring) { -dir * it / 4 } +
                                    fadeOut(tween(durationMillis = Motion.DUR_FAST))
                            )
                    }
                },
                label = "importFlow",
            ) { s ->
                when (s) {
                    is ImportUiState.Idle ->
                        MethodList(
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
                                    ),
                                )
                            },
                            onPaste = { showPasteDialog = true },
                            onPickImages = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onQrTransfer = { viewModel.openQrScan() },
                        )
                    is ImportUiState.QrScan ->
                        QrScanContent(
                            onDecoded = { viewModel.importFromQr(it) },
                            onSwitchToShare = { viewModel.openQrShare() },
                        )
                    is ImportUiState.QrShare ->
                        QrShareContent(
                            state = viewModel.qrShare,
                            onSwitchToScan = { viewModel.openQrScan() },
                        )
                    is ImportUiState.Parsing -> ParsingContent(viewModel.parsingNote)
                    is ImportUiState.OcrReview ->
                        viewModel.ocrReview?.let { review ->
                            OcrReviewContent(
                                review = review,
                                onUpdate = { index, draft -> viewModel.updateOcrDraft(index, draft) },
                                onRemove = { index -> viewModel.removeOcrDraft(index) },
                                onConfirm = { viewModel.confirmOcrReview() },
                                onBack = { viewModel.reset() },
                            )
                        }
                    is ImportUiState.Preview ->
                        PreviewContent(
                            changes = s.preview.changes,
                            summary = s.preview.summary(),
                            hasConflict = s.preview.conflictCount > 0,
                            applyConflicts = viewModel.applyConflicts,
                            onToggleConflicts = { viewModel.updateApplyConflicts(it) },
                            onConfirm = { viewModel.apply() },
                            onCancel = { viewModel.reset() },
                            sectionHint = s.preview.sectionHint(),
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
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("支持：分享口令 / JSON / HTML 表格 / CSV 文本") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            },
            confirmButton = {
                TapTextButton(
                    onClick = {
                        showPasteDialog = false
                        viewModel.importFromText(text)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("解析") }
            },
            dismissButton = {
                TapTextButton(onClick = { showPasteDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 导入流程的推进方向：前进从右侧滑入，回退（重新选择 / 重试）从左侧滑入 */
private fun importStateOrder(state: ImportUiState): Int =
    when (state) {
        is ImportUiState.Idle -> 0
        is ImportUiState.Parsing -> 1
        is ImportUiState.Error -> 1
        is ImportUiState.QrScan -> 1
        is ImportUiState.QrShare -> 1
        is ImportUiState.OcrReview -> 2
        is ImportUiState.Preview -> 3
        is ImportUiState.Done -> 4
    }

/** 顶栏标题随导入流程态切换 */
private fun topTitle(state: ImportUiState): String =
    when (state) {
        is ImportUiState.QrScan -> "扫码导入"
        is ImportUiState.QrShare -> "分享二维码"
        else -> "导入课表"
    }
