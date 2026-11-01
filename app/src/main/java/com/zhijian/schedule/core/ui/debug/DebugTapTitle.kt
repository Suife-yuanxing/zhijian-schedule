package com.zhijian.schedule.core.ui.debug

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors

/**
 * 隐藏调试入口的标题：连点 [DebugTapGate.DEFAULT_REQUIRED] 次，调出教务凭据的失败态面板。
 *
 * ## 为什么藏在标题上
 * 调试点需要一个「存在但不会被打扰」的家。三个候选各自的问题：
 * - 设置页里加一行：等于给所有用户看，与「隐藏」矛盾；
 * - 长按：与页面里其它长按（课块、删除）语义冲突；
 * - 标题连点：已经在 Android 系统设置里用了十年，用户对这个手势有肌肉记忆。
 * 而且标题本身不需要点击，连着点七下只可能是故意的。
 *
 * ## 为什么用 [combinedClickable] 而不是 clickable
 * 需要 `onClick = {}`：不给点击语义的话，连点在 Compose 里根本不会派发——
 * 而给一个空 lambda 又会让读屏软件把它念成一个可点按钮。
 * 所以显式把 `contentDescription` 设回标题文字本身，让无障碍树里它仍然只是个标题。
 *
 * ## 为什么中途要显示剩余次数
 * 不给反馈的连点在第二次之后就没人知道自己在干什么了，用户会放弃。
 * 从第 [HINT_AFTER] 次开始把「还差几次」写在标题旁边：已经投入了三次点击的人，
 * 值得被告知再点几下就有东西。
 *
 * @param title 标题文字
 * @param onUnlock 凑满次数时回调一次（随后计数归零，需要重新连点）
 * @param enabled 关掉时完全不接点击（正式版把它设为常量 false，见调用点）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DebugTapTitle(
    title: String,
    onUnlock: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val gate = remember(enabled) { DebugTapGate() }
    // 剩余次数的提示：只在连点进行中被点亮，凑满或超时后自然消失
    var remaining by remember(enabled) { mutableStateOf(0) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier =
                Modifier
                    .semantics { contentDescription = title }
                    .combinedClickable(
                        enabled = enabled,
                        // 让水波纹不出现：标题是文字，不是按钮，涟漪会让人以为这里能点
                        indication = null,
                        interactionSource = null,
                        onClick = {
                            val unlocked = gate.tap(System.currentTimeMillis())
                            val left = DebugTapGate.DEFAULT_REQUIRED - gate.progress()
                            // 一次轻微的确认：连点七下没有任何触感反馈会让人怀疑点空了。
                            // 用 ContextClick（轻、短）而不是 Confirm——后者留给「完成态」
                            if (unlocked) {
                                remaining = 0
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                onUnlock()
                            } else if (left in 1 until DebugTapGate.DEFAULT_REQUIRED - HINT_AFTER) {
                                remaining = left
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            } else {
                                remaining = 0
                            }
                        },
                    ),
        )
        if (remaining > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "还差 " + remaining + " 次",
                color = colors.accent,
                fontSize = 11.sp,
            )
        }
    }
}

/** 从第几次点击之后开始提示剩余次数：前几次不打扰，免得正常点击蹭到就冒出提示 */
private const val HINT_AFTER = 2
