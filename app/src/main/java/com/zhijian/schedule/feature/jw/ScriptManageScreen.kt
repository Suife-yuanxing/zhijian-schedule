package com.zhijian.schedule.feature.jw

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.metal.MetalFields

/**
 * 课程脚本管理页：脚本列表（内置/本地/远程）+ 仓库地址 + 检查更新 / 导入 .js。
 *
 * 状态机在 [ScriptManageViewModel]，本文件只有三个区块：工具栏、脚本列表、仓库区，
 * 以及删除确认弹窗。列表项 [ScriptRow] 是纯展示 + 回调。
 */
@Composable
internal fun ScriptManageScreen(
    onBack: () -> Unit,
    viewModel: ScriptManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var indexDraft by remember(state.indexUrl) { mutableStateOf(state.indexUrl) }
    var pendingDelete by remember { mutableStateOf<ScriptOption?>(null) }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(viewModel::importFromUri)
        }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        SecondaryTopBar(title = "课程脚本", onBack = onBack)

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = "脚本负责把各学校的教务页面解析成课表；安装后可在「教务系统导入」页右上角选择运行。",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            if (state.options.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有脚本。点下方「导入 .js」安装，或配置仓库后「检查更新」。",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            }
            items(state.options, key = { it.id }) { option ->
                ScriptRow(
                    option = option,
                    onToggle = { viewModel.toggle(option.id, it) },
                    onDelete = { pendingDelete = option },
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("脚本仓库", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "指向 index.json 的 https 地址；留空则只能用内置与本地导入",
                color = colors.textMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = indexDraft,
                    onValueChange = { indexDraft = it },
                    placeholder = { Text("https://…/index.json", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TapTextButton(onClick = { viewModel.setIndexUrl(indexDraft) }) { Text("保存") }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TapTextButton(
                    onClick = viewModel::checkUpdates,
                    enabled = !state.busy,
                ) { Text("检查更新", fontSize = 13.sp) }
                TapTextButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !state.busy,
                ) { Text("导入 .js", fontSize = 13.sp) }
            }
        }
    }

    pendingDelete?.let { option ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「${option.name}」吗？内置脚本删除后可随时重装恢复。") },
            confirmButton = {
                TapTextButton(onClick = {
                    viewModel.remove(option.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TapTextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 单个脚本：名称 / 副标题 / 入口提示 + 内置标记或删除按钮 + 启停开关 */
@Composable
private fun ScriptRow(
    option: ScriptOption,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(option.subtitle(), color = colors.textMuted, fontSize = 11.sp)
            if (option.entryHint.isNotBlank()) {
                Text(option.entryHint, color = colors.textMuted, fontSize = 11.sp)
            }
        }
        if (option.origin == "builtin") {
            Text(
                text = "内置",
                color = colors.accent,
                fontSize = 11.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            TapIconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.textMuted)
            }
        }
        JellySwitch(checked = option.enabled, onCheckedChange = onToggle)
    }
}
