package com.zhijian.schedule.feature.importer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.buttonFeedback

/** 解析中：进度指示 + 当前阶段说明 */
@Composable
internal fun ParsingContent(note: String) {
    val colors = LocalCourseColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent)
            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, color = colors.textMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun DoneContent(summary: String, onDone: () -> Unit) {
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
            modifier = Modifier.buttonFeedback(doneSource, pressedScale = 0.96f),
        ) { Text("返回课表") }
    }
}

@Composable
internal fun ErrorContent(message: String, onRetry: () -> Unit) {
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
