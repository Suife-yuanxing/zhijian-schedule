package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.metal.MetalFields

/** 「我的」页的两个弹窗：自定义提醒分钟数、隐私政策摘要 */

@Composable
internal fun CustomReminderDialog(
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
                colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
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
internal fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
        confirmButton = { TapTextButton(onClick = onDismiss) { Text("我知道了") } },
    )
}
