package com.zhijian.schedule.feature.jw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.TapTextButton

/**
 * 吃掉「向上」方向的剩余拖动与甩动速度，Material3 弹层本体因此收不到任何向上的增量，
 * 也就不会在已到顶时被 fling 拖着越过锚点再 spring 回弹（M3 已知缺陷）。
 * 向下方向原样放行，保留下拉关闭弹层的手势。
 */
private object ConsumeUpwardScrollConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = if (available.y < 0f) Offset(available.x, available.y) else Offset.Zero

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity = if (available.y < 0f) Velocity(available.x, available.y) else Velocity.Zero
}

/**
 * 导入流程的「选择脚本」步骤：脚本选择底部弹层。
 *
 * 命中当前站点的排前；底部入口进管理页（spec §4）。选项列表由 [optionsProvider]
 * 异步给出（命中判定需要当前页面的 host），因此这里自己负责 loading / 空态 / 列表三态。
 *
 * 列表区域自带垂直滚动：内容超出时在内部滚动，弹层本体不再接管拖拽，
 * 避免内容超过一屏后手势全部落到弹层上导致反复回弹、底部按钮永远够不着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptPickerDialog(
    pageUrl: String,
    optionsProvider: suspend () -> List<ScriptOption>,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onRun: (ScriptOption) -> Unit,
) {
    val colors = LocalCourseColors.current
    val options = remember(pageUrl) { mutableStateOf<List<ScriptOption>?>(null) }
    LaunchedEffect(pageUrl) {
        options.value = optionsProvider()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(ConsumeUpwardScrollConnection)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "用脚本导入",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            val list = options.value
            when {
                list == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                }
                list.isEmpty() -> {
                    Text(
                        "还没有可用的课程脚本。可在「管理脚本」里导入 .js 或从仓库安装。",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
                else -> {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        list.forEach { option ->
                            ScriptOptionRow(option, onRun)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TapTextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                TapTextButton(onClick = onManage, modifier = Modifier.weight(1f)) { Text("管理脚本…") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 单个脚本条目：命中站点的排在前面，不可运行的置灰并说明原因 */
@Composable
private fun ScriptOptionRow(
    option: ScriptOption,
    onRun: (ScriptOption) -> Unit,
) {
    val colors = LocalCourseColors.current
    val enabled = option.runnable
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onRun(option) }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = option.name,
            color = if (enabled) colors.text else colors.textMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = option.subtitle(),
            color = colors.textMuted,
            fontSize = 11.sp,
        )
        if (option.entryHint.isNotBlank()) {
            Text(
                text = option.entryHint,
                color = colors.textMuted,
                fontSize = 11.sp,
            )
        }
        if (option.matched) {
            Text(
                text = "适用于当前站点",
                color = colors.accent,
                fontSize = 11.sp,
            )
        }
        if (!option.enabled) {
            Text(text = "已在管理页停用", color = colors.textMuted, fontSize = 11.sp)
        } else if (!option.matched && option.hosts.isNotEmpty()) {
            Text(
                text = "当前页面不属于该脚本适用的站点",
                color = colors.textMuted,
                fontSize = 11.sp,
            )
        }
    }
}
