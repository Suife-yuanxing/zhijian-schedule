package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.metal.LocalMetalClock
import com.zhijian.schedule.core.ui.metal.metalPointer
import com.zhijian.schedule.core.ui.metal.metalSurface
import com.zhijian.schedule.core.ui.metal.rememberMetalTouchState
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
    val metal = LocalCoursePreset.current.metal
    val metalClock = LocalMetalClock.current
    val metalTouch = rememberMetalTouchState()
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
        if (metal != null) {
            // 金属主题：加号是一枚悬在页面上的水银键帽——球冠高光 + 按压凹陷 + 拉丝
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = IslandContentPadding)
                    .size(56.dp)
                    .onGloballyPositioned { fabBounds = it.boundsInRoot() }
                    .graphicsLayer {
                        val s = fabScale.value
                        scaleX = s
                        scaleY = s
                        alpha = s.coerceIn(0f, 1f)
                    }.shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.30f),
                        spotColor = Color.Black.copy(alpha = 0.50f),
                    ).metalSurface(
                        shape = RoundedCornerShape(percent = 50),
                        touch = metalTouch,
                        clock = metalClock,
                        chromeOverride = 0.95f,
                        faceOverride = colors.accent,
                    ).metalPointer(metalTouch)
                    .buttonFeedback(fabSource, pressedScale = 0.92f)
                    .clickable(
                        interactionSource = fabSource,
                        indication = null,
                        onClick = { onAddCourseFromFab(fabBounds) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加课程", tint = colors.onAccent)
            }
        } else {
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
}
