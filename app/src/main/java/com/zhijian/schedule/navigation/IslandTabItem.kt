package com.zhijian.schedule.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.rememberIslandPalette
import com.zhijian.schedule.core.ui.keyframed

/** 浮岛条目：图标交互沿用原有行为（按压蓄势 + 果冻弹跳 + 描边实心切换） */
@Composable
internal fun RowScope.IslandTabItem(
    tab: TabItem,
    selected: Boolean,
    bounceTrigger: Int,
    onClick: () -> Unit,
    collapse: IslandCollapseState? = null,
) {
    val island = rememberIslandPalette()
    val pressSource = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue = if (selected) island.selected else island.muted,
        // 与胶囊滑动同拍渐变，避免图标先行变色、胶囊还在路上的割裂感
        animationSpec = tween(Motion.DurMedium, easing = Motion.EaseOut),
        label = "tabContentColor",
    )
    // 标签的显隐：收敛到一半以下就淡出，让浮岛落成「纯图标胶囊」。
    // 用目标态而不是逐帧插值驱动 alpha——逐帧淡出会在慢速滚动时糊成半透明废影。
    val labelAlpha by animateFloatAsState(
        targetValue = if (collapse == null || collapse.expanded) 1f else 0f,
        animationSpec = tween(Motion.DurFast),
        label = "tabLabelAlpha",
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = pressSource,
                // 白色浮岛上涟漪会变成一块浅灰矩形"虚影"，因此只保留图标按压+弹跳，不加水波纹
                indication = null,
                role = Role.Tab,
                onClick = {
                    onClick()
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BouncyTabIcon(
            filled = tab.iconFilled,
            outlined = tab.iconOutlined,
            selected = selected,
            bounceTrigger = bounceTrigger,
            interactionSource = pressSource,
            contentDescription = tab.label,
            tint = contentColor,
        )
        // 收敛态把标签占位一起收掉，否则图标会被顶偏：
        // 显式动画槽高（2 + 13dp），配合 clipToBounds 让文字随槽一起「卷」进去，
        // 比单纯 alpha=0 干净——不会留下一块看不见却占位的空气，图标能真正居中。
        // 槽高 = 2dp 间距 + 11sp 文字行高（约 14.7dp）的实测上限；留一点余量，
        // 给得紧一点就会被 clipToBounds 切掉字的下缘（首版给了 15dp 就裁掉了半截）
        val labelSlot by animateDpAsState(
            targetValue = if (collapse == null || collapse.expanded) 19.dp else 0.dp,
            animationSpec = tween(Motion.DurFast),
            label = "tabLabelSlot",
        )
        Box(Modifier.height(labelSlot).clipToBounds()) {
            Column {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                )
            }
        }
    }
}

// 水波涟漪仍然不加：白色浮岛上会变成一块浅灰矩形「虚影」（见 IslandTabItem 的 indication = null）。
// 形变则改由速度实时折算（见 island/IslandIndicator.kt），不再是被删掉的那条独立关键帧。

// ---------- 图标弹跳（与改造前一致：描边↔实心 + 果冻弹跳） ----------

/** 选中态静息缩放（与选中胶囊叠加强化选中感知） */
private const val TAB_REST_SCALE = 1.08f

/** 一次弹跳的节拍总长 */
private const val TAB_BOUNCE_MS = 520

/** 弹跳通道关键帧（相对静息值）：压扁蓄力 → 上跳过冲 → 落位微颤 */
private val TAB_BUMP_X = arrayOf(0f to 1f, 0.13f to 1.07f, 0.38f to 0.95f, 0.63f to 1.02f, 1f to 1f)
private val TAB_BUMP_Y = arrayOf(0f to 1f, 0.13f to 0.88f, 0.38f to 1.13f, 0.63f to 0.98f, 1f to 1f)
private val TAB_HOP_Y = arrayOf(0f to 0f, 0.27f to -5.5f, 0.58f to -0.8f, 0.73f to -1.6f, 1f to 0f)
private val TAB_TILT_Z = arrayOf(0f to 0f, 0.17f to -7f, 0.44f to 4.5f, 0.69f to -2f, 1f to 0f)

/**
 * 底部导航图标：未选中描边、选中实心（形态切换），点击时一套「果冻」弹跳——
 * 先压扁蓄力，再带旋转上跳、拉伸过冲后落位微颤；按下即缩小蓄势。
 * 首次组合直接落位不播动画；复点当前 tab 由 [bounceTrigger] 重播同一套弹跳。
 */
@Composable
private fun BouncyTabIcon(
    filled: ImageVector,
    outlined: ImageVector,
    selected: Boolean,
    bounceTrigger: Int,
    interactionSource: MutableInteractionSource,
    contentDescription: String,
    tint: Color,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = Motion.PressSpring,
        label = "tabPress",
    )
    val restScale by animateFloatAsState(
        targetValue = if (selected) TAB_REST_SCALE else 1f,
        animationSpec = Motion.PopSpring,
        label = "tabRestScale",
    )
    // 弹跳由单一进度驱动 0→1，各通道经关键帧映射求值，保证多通道严格同拍
    val bounce = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(selected, bounceTrigger) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        if (selected) {
            bounce.snapTo(0f)
            bounce.animateTo(1f, tween(TAB_BOUNCE_MS, easing = LinearEasing))
        } else {
            bounce.snapTo(1f)
        }
    }

    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (fadeIn(tween(Motion.DurFast)) + scaleIn(tween(Motion.DurFast), initialScale = 0.7f))
                .togetherWith(fadeOut(tween(Motion.DurFast)) + scaleOut(tween(Motion.DurFast), targetScale = 0.7f))
        },
        contentAlignment = Alignment.Center,
        label = "tabIconMorph",
        modifier = Modifier.graphicsLayer {
            val p = bounce.value
            scaleX = restScale * press * keyframed(p, *TAB_BUMP_X)
            scaleY = restScale * press * keyframed(p, *TAB_BUMP_Y)
            translationY = keyframed(p, *TAB_HOP_Y).dp.toPx()
            rotationZ = keyframed(p, *TAB_TILT_Z)
        },
    ) { sel ->
        Icon(
            imageVector = if (sel) filled else outlined,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}
