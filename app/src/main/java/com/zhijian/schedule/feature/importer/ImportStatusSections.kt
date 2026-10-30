package com.zhijian.schedule.feature.importer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.AnimatedGradientCheckMark
import com.zhijian.schedule.core.ui.RippleLoader
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.accentGradient
import com.zhijian.schedule.core.ui.buttonFeedback

/** 导入成功的语义色：绿。与 [ImportChangeKind.ADDED] 的徽标同色，全项目只有这一个成功绿 */
private val SUCCESS_GREEN = Color(0xFF2BB673)

/**
 * 解析中：进度指示 + 当前阶段说明。
 *
 * 用 [RippleLoader] 而非系统转圈：解析要读完整个文件（xlsx / ics / 课表截图），
 * 用户会盯着这块空白等好几秒。系统 CircularProgressIndicator 是匀速旋转，没有节拍起伏，
 * 几秒后读起来像「卡住了」；涟漪有明确的扩散周期，同一段时间里能数出「它在推进」。
 */
@Composable
internal fun ParsingContent(note: String) {
    val colors = LocalCourseColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RippleLoader(
                modifier = Modifier.size(72.dp),
                color = colors.accent,
                rings = 3,
            )
            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, color = colors.textMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun DoneContent(
    summary: String,
    onDone: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    // 完成标记：勾是「画上去」的，不是整枚图标缩放冒出来。
    // 用 arrived 当闸门而不是直接 checked = true —— 首次组合时 animateFloatAsState 的
    // 初值就等于目标值，不会播动画；必须先 false 再翻 true 才有落笔过程。
    // 配色从成功绿派生（压深起点 / 提亮终点），所以它属于主题，而不是贴上去的紫青色。
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedGradientCheckMark(
            checked = arrived,
            markSize = 48.dp,
            gradientColors = remember { accentGradient(SUCCESS_GREEN) },
            // 页面底色是浅纸色，白色 24% 的半透明底会完全消失；描边用同一个绿压深一档
            idleRingColor = SUCCESS_GREEN,
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
            modifier = Modifier.buttonFeedback(doneSource, pressedScale = 0.96f),
        ) { Text("返回课表") }
    }
}

@Composable
internal fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
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
        TapButton(onClick = onRetry) { Text("重新选择") }
    }
}
