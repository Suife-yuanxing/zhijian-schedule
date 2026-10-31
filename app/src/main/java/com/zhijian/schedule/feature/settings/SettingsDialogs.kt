package com.zhijian.schedule.feature.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.repository.TimetableMetaPlan
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.metal.MetalFields
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 设置页的两个编辑弹窗：课表信息、单个小节的上课时间 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableEditDialog(
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
    var weeks by remember { mutableStateOf(initialWeeks.coerceIn(1, TimetableMetaPlan.MAX_WEEKS)) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课表名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = semester,
                    onValueChange = { semester = it },
                    label = { Text("学期（如 2026 秋季）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                SemesterDateField(
                    date = TimeUtils.parseDate(startDate),
                    onClick = { showDatePicker = true },
                )
                Spacer(Modifier.height(12.dp))
                Text("总周数", fontSize = 13.sp, color = LocalCourseColors.current.textMuted)
                Spacer(Modifier.height(6.dp))
                WeekPresetsRow(weeks = weeks, onSelect = { weeks = it })
                Spacer(Modifier.height(8.dp))
                WeekStepper(weeks = weeks, onChange = { weeks = it })
                SemesterTimeline(startDate = TimeUtils.parseDate(startDate), totalWeeks = weeks)
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

    if (showDatePicker) {
        WeekMondayPicker(
            initial = TimeUtils.parseDate(startDate),
            onPick = { picked ->
                startDate = TimeUtils.formatDate(picked)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
internal fun SectionEditDialog(
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
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始（HH:mm）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
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

/** 开学日字段按钮：显示「yyyy年M月d日 · 周一」，点开日历；未设置时引导点选 */
@Composable
private fun SemesterDateField(
    date: LocalDate?,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(source, pressedScale = 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("第 1 周周一", fontSize = 12.sp, color = colors.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text =
                date?.let {
                    "${it.year}年${it.monthValue}月${it.dayOfMonth}日 · ${TimeUtils.dayName(it.dayOfWeek.value)}"
                } ?: "点选开学日期",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (date == null) colors.textMuted else colors.text,
        )
    }
}

/** 选周一日历：任意日期对齐到所在周周一；DatePicker 以 UTC 计日，转换需走 UTC */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekMondayPicker(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis =
                initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TapTextButton(onClick = {
                val millis = state.selectedDateMillis ?: return@TapTextButton
                val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                onPick(picked.minusDays((picked.dayOfWeek.value - 1).toLong()))
            }) { Text("确定") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** 总周数预设档：单选分段（与周格同一选中语言：主色描边 + 浅底） */
private val WEEK_PRESETS = listOf(16, 18, 20, 22)

@Composable
private fun WeekPresetsRow(
    weeks: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WEEK_PRESETS.forEach { preset ->
            val selected = weeks == preset
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .pressScale(source, pressedScale = 0.94f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceVariant,
                    ).border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) colors.accent else colors.border,
                        shape = RoundedCornerShape(11.dp),
                    ).clickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                    ) { onSelect(preset) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$preset",
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.accent else colors.text,
                )
            }
        }
    }
}

/** 周数步进器：单击 ±1，长按连发（先 420ms 停顿再 85ms 加速） */
@Composable
private fun WeekStepper(
    weeks: Int,
    onChange: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(13.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            symbol = "−",
            label = "减少一周",
            enabled = weeks > 1,
        ) { onChange(weeks - 1) }
        Text(
            text = "$weeks 周",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton(
            symbol = "＋",
            label = "增加一周",
            enabled = weeks < TimetableMetaPlan.MAX_WEEKS,
        ) { onChange(weeks + 1) }
    }
}

@Composable
private fun StepButton(
    symbol: String,
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    val held by source.collectIsPressedAsState()
    // 连发唯一驱动是这里的 LaunchedEffect：按下立即一步、停顿后加速；
    // clickable 的 onClick 故意留空，避免「按下已计步、松手又 +1」的双重触发。
    LaunchedEffect(held) {
        if (held && enabled) {
            onStep()
            delay(420)
            while (held && enabled) {
                onStep()
                delay(85)
            }
        }
    }
    Box(
        Modifier
            .size(width = 46.dp, height = 38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) colors.accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = { /* 计步全部由上方 LaunchedEffect 负责 */ },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.text else colors.textMuted.copy(alpha = 0.5f),
        )
    }
}

/** 学期时间轴：N 段周格实时预览（过去淡主色 / 本周实心 / 未来浅底）+ 进度说明 */
@Composable
private fun SemesterTimeline(
    startDate: LocalDate?,
    totalWeeks: Int,
) {
    val colors = LocalCourseColors.current
    val model =
        remember(startDate, totalWeeks) {
            SemesterTimelinePlan.build(startDate, totalWeeks, LocalDate.now())
        }
    if (model.weeks.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            model.weeks.forEach { week ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (week.state) {
                                SemesterTimelinePlan.WeekState.PAST -> colors.accent.copy(alpha = 0.30f)
                                SemesterTimelinePlan.WeekState.NOW -> colors.accent
                                SemesterTimelinePlan.WeekState.FUTURE -> colors.surfaceVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        val endText =
            model.endDate?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "—"
        Text(
            text =
                when (model.status) {
                    SemesterTimelinePlan.Status.ACTIVE -> "第 ${model.currentWeek} 周进行中 · 至 $endText"
                    SemesterTimelinePlan.Status.NOT_STARTED -> "未开学 · 至 $endText"
                    SemesterTimelinePlan.Status.FINISHED -> "已结课 · 至 $endText"
                },
            fontSize = 11.sp,
            color = colors.textMuted,
        )
    }
}
