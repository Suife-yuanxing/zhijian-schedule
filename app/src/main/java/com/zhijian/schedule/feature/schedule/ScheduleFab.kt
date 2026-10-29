package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.navigation.IslandContentPadding

/**
 * 课表页的加号按钮：本次进程第一次进入课表时弹一次，并把按钮自身的 root 坐标交给调用方，
 * 供「从加号圆形扩散进编辑页」用。
 */
@Composable
internal fun ScheduleFab(
    playEntrance: Boolean,
    onAddCourseFromFab: (Rect) -> Unit,
) {
    val colors = LocalCourseColors.current
    val fabSource = remember { MutableInteractionSource() }
    // 加号在 Compose 根坐标系里的位置：编辑页从这个圆里长出来
    var fabBounds by remember { mutableStateOf(Rect.Zero) }
    // 与课块同一拍：只有本次进程第一次进入课表才弹。用 Animatable 而不是 animateFloatAsState，
    // 是为了新的回合能瞬间回 0（拉目标值会先播一段收缩）。
    // 不播时 snapshot 到 1f 落位——加号是常驻控件，切页时它跟着页面一起滑就行，
    // 再叠一次「缩回 0 再长出来」只会强化「整页刷新」的观感。
    val fabScale = remember { Animatable(if (playEntrance) 0f else 1f) }
    LaunchedEffect(playEntrance) {
        if (playEntrance) {
            fabScale.snapTo(0f)
            fabScale.animateTo(1f, Motion.PopSpring)
        } else {
            fabScale.snapTo(1f)
        }
    }
    Box(Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { onAddCourseFromFab(fabBounds) },
            interactionSource = fabSource,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = IslandContentPadding)
                    .size(48.dp)
                    .onGloballyPositioned { fabBounds = it.boundsInRoot() }
                    .buttonFeedback(fabSource, pressedScale = 0.9f)
                    .graphicsLayer {
                        val s = fabScale.value
                        scaleX = s
                        scaleY = s
                        alpha = s.coerceIn(0f, 1f)
                    },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加课程")
        }
    }
}
