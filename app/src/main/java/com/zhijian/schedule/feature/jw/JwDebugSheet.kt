package com.zhijian.schedule.feature.jw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.island.IslandHintTone

/**
 * 隐藏调试入口：手动触发教务凭据的失败态浮岛。
 *
 * ## 为什么需要它
 * 「填入失败」与「保存失败」两条路径平时造不出来：前者要求页面停在登录页却没有密码框，
 * 后者要求 Keystore 密钥坏掉。没有手动入口，这两条提示只有在真出事故时才会被执行到——
 * 而那正是最不该第一次看见它的时候。
 *
 * ## 为什么只列这几条
 * 面板每多一个按钮，「哪个才是我要验的」就多一分犹豫。这里只列**造不出来的**：
 * 成功态随便登录一次就有，不需要入口；清除凭据设置页本来就有。
 * 最后一行留了一条成功态做对照——两种语气只有在同一屏里并排看过，
 * 才知道它们是否真的一眼可分。
 *
 * ## 文案从哪来
 * 每一条的说明都由**同一个函数**生成，与线上提示用的是同一个（见 [JwDebugAction.text]）。
 * 面板里若手写一份文案，它就会随着时间与线上越差越远，
 * 于是调试时看到的那句话其实没人真会看到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JwDebugSheet(
    onDismiss: () -> Unit,
    onShow: (IslandHintTone, String) -> Unit,
) {
    val colors = LocalCourseColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "调试 · 教务凭据提示",
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = "每一项都会立刻弹出一条真实的浮岛提示，与线上走同一套组件。",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(14.dp))
            DebugTapTargets.forEach { target ->
                DebugAction(target = target, colors = colors, onShow = onShow)
            }
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 10.dp))
            DebugAction(target = ControlTarget, colors = colors, onShow = onShow)
        }
    }
}

/** 面板里的一行：左侧说明、右侧「触发」 */
@Composable
private fun DebugAction(
    target: DebugTapTarget,
    colors: CourseColors,
    onShow: (IslandHintTone, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(target.label, color = colors.text, fontSize = 13.5.sp)
            Text(target.text, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        TapTextButton(onClick = { onShow(target.tone, target.text) }) {
            Text("触发", fontSize = 13.sp)
        }
    }
}

/**
 * 面板里的一行：标签 + 语气 + 要显示的文案。
 *
 * 文案在构造时就生成好（而不是点下去时才算），因为它同时是列表里的说明文字——
 * 用户在点之前就该看见他会看到什么。
 */
internal data class DebugTapTarget(
    val label: String,
    val tone: IslandHintTone,
    val text: String,
)

/** 四条造不出来的失败：两种填入失败 + 三种保存失败里挑最典型的两条 */
internal val DebugTapTargets: List<DebugTapTarget> =
    listOf(
        DebugTapTarget(
            label = "填入失败 · 找不到登录框",
            tone = IslandHintTone.Failed,
            text = credentialFillFailedText(JwCredentialFillFailure.NoInput),
        ),
        DebugTapTarget(
            label = "填入失败 · 登录框拒绝写入",
            tone = IslandHintTone.Failed,
            text = credentialFillFailedText(JwCredentialFillFailure.WriteRejected),
        ),
        DebugTapTarget(
            label = "保存失败 · 本机密钥不可用",
            tone = IslandHintTone.Failed,
            text = credentialSaveFailedText(JwCredentialSaveFailure.KeyUnavailable),
        ),
        DebugTapTarget(
            label = "保存失败 · 写入存储失败",
            tone = IslandHintTone.Failed,
            text = credentialSaveFailedText(JwCredentialSaveFailure.Storage),
        ),
    )

/** 对照用的成功态：两种语气并排看过，才知道它们是否真的一眼可分 */
internal val ControlTarget: DebugTapTarget =
    DebugTapTarget(
        label = "对照 · 成功态",
        tone = IslandHintTone.Done,
        text = "已填入保存的教务账号（调试样例），确认无误后点登录",
    )
