package com.zhijian.schedule.feature.exam

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.cascadeIn
import com.zhijian.schedule.core.ui.metal.MetalFields
import com.zhijian.schedule.core.ui.pressScale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ExamScreen(
    onBack: () -> Unit,
    onNeedsLogin: () -> Unit = {},
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    val now = remember { LocalDateTime.now() }
    var editing by remember { mutableStateOf<ExamEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ExamEntity?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(Modifier.fillMaxSize()) {
            SecondaryTopBar(
                title = "考试安排",
                onBack = onBack,
                actions = {
                    if (state.jwAvailable) {
                        TapIconButton(onClick = viewModel::importFromJw) {
                            if (state.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "从教务导入", tint = colors.text)
                            }
                        }
                    }
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                state.message?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner(msg, colors.accent, onClick = viewModel::consumeMessage)
                }
                if (state.needsLogin) {
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner("登录会话已过期，点击前往教务导入页重新登录", colors.accent) { onNeedsLogin() }
                }
                if (state.exams.isEmpty() && !state.importing) {
                    Spacer(Modifier.height(64.dp))
                    Text(
                        "还没有考试安排",
                        fontSize = 15.sp,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "点右下角 + 手动添加，或用右上角从教务导入",
                        fontSize = 12.sp,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                    )
                } else {
                    val (upcoming, past) = state.exams.partition { it.examTime >= System.currentTimeMillis() }
                    if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        StaggerIn(index = 0, stepMs = 40, durationMs = 280) {
                            GroupLabel("即将到来")
                        }
                        upcoming.forEachIndexed { index, exam ->
                            ExamRow(
                                exam,
                                now,
                                past = false,
                                entranceDelayMs = (60L + index * 24).coerceAtMost(240L),
                                onClick = {
                                    editing = exam
                                    showEditor = true
                                },
                                onLongClick = { pendingDelete = exam },
                            )
                        }
                    }
                    if (past.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        StaggerIn(index = 1, stepMs = 40, durationMs = 280) {
                            GroupLabel("已结束")
                        }
                        past.forEachIndexed { index, exam ->
                            ExamRow(
                                exam,
                                now,
                                past = true,
                                entranceDelayMs = (140L + index * 24).coerceAtMost(320L),
                                onClick = {},
                                onLongClick = { pendingDelete = exam },
                            )
                        }
                    }
                }
                // navigationBarsPadding 把手势条高度补进预留：列表铺到屏幕底，末项不被遮
                Spacer(Modifier.navigationBarsPadding().height(96.dp))
            }
        }
        FloatingActionButton(
            onClick = {
                editing = null
                showEditor = true
            },
            containerColor = colors.accent,
            contentColor = colors.bg,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    // 浮在手势条正上方
                    .navigationBarsPadding()
                    .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加考试")
        }
    }

    if (showEditor) {
        ExamEditorDialog(
            initial = editing,
            onDismiss = {
                showEditor = false
                editing = null
            },
            onConfirm = { name, room, seat, millis, note ->
                viewModel.save(name, room, seat, millis, note, editing?.id)
                showEditor = false
                editing = null
            },
        )
    }

    pendingDelete?.let { exam ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除考试") },
            text = { Text("确定删除「${exam.name}」的考试安排吗？") },
            confirmButton = {
                TapTextButton(onClick = {
                    viewModel.delete(exam.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TapTextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    val colors = LocalCourseColors.current
    Text(
        text,
        color = colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun NoticeBanner(
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Text(
        text = text,
        color = tint,
        fontSize = 12.sp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExamRow(
    exam: ExamEntity,
    now: LocalDateTime,
    past: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    entranceDelayMs: Long = -1L,
) {
    val colors = LocalCourseColors.current
    val alpha = if (past) 0.5f else 1f
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .cascadeIn(entranceDelayMs, startScale = 0.92f, riseDp = 8f)
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            // 已结束的行不可交互，按压反馈一并关掉
            .pressScale(source, pressedScale = 0.98f, enabled = !past)
            .combinedClickable(
                enabled = true,
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = { if (!past) onClick() },
                onLongClick = onLongClick,
            ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                exam.name,
                color = colors.text.copy(alpha = alpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            val where =
                listOf(
                    exam.room.takeIf { it.isNotBlank() },
                    exam.seat.takeIf { it.isNotBlank() }?.let { "$it 号座" },
                ).filterNotNull().joinToString(" · ")
            if (where.isNotBlank()) {
                Text(where, color = colors.textMuted.copy(alpha = alpha), fontSize = 11.sp)
            }
            Text(
                ExamCountdown.detailLine(exam.examTime),
                color = colors.textMuted.copy(alpha = alpha),
                fontSize = 11.sp,
            )
        }
        Text(
            ExamCountdown.badge(exam.examTime, now),
            color = if (past) colors.textMuted else colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ExamEditorDialog(
    initial: ExamEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, room: String, seat: String, examTimeMillis: Long, note: String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var date by remember(initial) {
        mutableStateOf(
            initial?.examTime?.let {
                DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ExamCountdown.local(it))
            } ?: LocalDate.now().toString(),
        )
    }
    var time by remember(initial) {
        mutableStateOf(
            initial?.examTime?.let {
                DateTimeFormatter.ofPattern("HH:mm").format(ExamCountdown.local(it))
            } ?: "14:00",
        )
    }
    var room by remember(initial) { mutableStateOf(initial?.room.orEmpty()) }
    var seat by remember(initial) { mutableStateOf(initial?.seat.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }

    val dateOk = runCatching { LocalDate.parse(date) }.isSuccess
    val timeOk = runCatching { LocalTime.parse(time) }.isSuccess
    val valid = name.isNotBlank() && dateOk && timeOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加考试" else "编辑考试") },
        text = {
            Column {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },
                    label = { Text("课程名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("日期 2026-11-02") },
                        isError = !dateOk,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("时间 14:00") },
                        isError = !timeOk,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = room,
                    onValueChange = { if (it.length <= 30) room = it },
                    label = { Text("考场") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = seat,
                    onValueChange = { if (it.length <= 10) seat = it },
                    label = { Text("座位号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = note,
                    onValueChange = { if (it.length <= 50) note = it },
                    label = { Text("备注（如：闭卷）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TapTextButton(
                enabled = valid,
                onClick = {
                    val millis =
                        LocalDate
                            .parse(date)
                            .atTime(LocalTime.parse(time))
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    onConfirm(name.trim(), room.trim(), seat.trim(), millis, note.trim())
                },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
