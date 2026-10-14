package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 全局动效语言：灵动有弹性。
 * 所有时长 / 弹簧 / 缓动集中于此，保证各页面观感一致（和谐、自然）。
 */
object Motion {
    /** 快速反馈：水波纹、渐隐 */
    const val DurFast = 150

    /** 标准过渡：淡入、局部布局切换 */
    const val DurMedium = 260

    /** 慢速登场：交错入场、进度扫描 */
    const val DurSlow = 420

    /** 标准缓动：快速起步、缓慢收尾 */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 按压回弹：按下干脆、松手轻微回弹 */
    val PressSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 620f)

    /** 选中弹跳：chip / 图标选中时的俏皮放大 */
    val PopSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 480f)

    /** 选中弹起：快速吸附、轻微过冲——课程块「被拿起」，与详情面板入场连成一体 */
    val SelectionEnter = spring<Float>(dampingRatio = 0.52f, stiffness = 520f)

    /** 选中恢复：干脆回落不拖尾——与详情面板滑出并行播完，消除「等面板走完才复位」的滞后感 */
    val SelectionExit = tween<Float>(durationMillis = 130, easing = EaseOut)

    /** 整屏横滑转场：无过冲，保证大位移干净利落 */
    val SlideSpring = spring<IntOffset>(
        dampingRatio = 1f,
        stiffness = 420f,
        visibilityThreshold = IntOffset(1, 1),
    )

    /** 级联入场：课块随页面切换涌入的落位弹簧（轻微过冲、收尾干净） */
    val CascadeEnter = spring<Float>(dampingRatio = 0.62f, stiffness = 460f)

    /** 底部浮岛选中胶囊滑动：流体转移、轻微过冲后落位即稳 */
    val FluidSlide = spring<Dp>(dampingRatio = 0.72f, stiffness = 480f)
}

/**
 * 按压缩放反馈。与 clickable 共用同一个 [interactionSource]，
 * 并放在 clickable 外层，使缩放同时作用于背景与水波纹。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.PressSpring,
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 选中弹跳：选中项放大少许并带弹性落点（FilterChip / 日期条 / 色板圆点）。 */
@Composable
fun Modifier.selectedPop(selected: Boolean, popScale: Float = 1.06f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) popScale else 1f,
        animationSpec = Motion.PopSpring,
        label = "selectedPop",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 交错登场：内容始终占位，只做透明度 + 上浮过渡，避免布局跳动。
 * [index] 越大登场越晚（步进 [stepMs]，封顶 [maxIndex]）。
 * 注意：内部是 Box，[content] 应只放单一子项；需要间距请在 StaggerIn 外层放 Spacer，
 * 写在 content 里只会与内容叠放、被静默吞掉。
 */
@Composable
fun StaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    stepMs: Long = 36,
    maxIndex: Int = 8,
    content: @Composable () -> Unit,
) {
    var arrived by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(maxIndex) * stepMs)
        arrived = 1f
    }
    val progress by animateFloatAsState(
        targetValue = arrived,
        animationSpec = tween(durationMillis = Motion.DurSlow, easing = Motion.EaseOut),
        label = "staggerIn",
    )
    Box(
        modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 20.dp.toPx()
        },
    ) {
        content()
    }
}

/**
 * 级联入场：延迟 [delayMs] 后淡入 + 弹簧放大 + 自下而上落位（课块随 tab 切页涌入）。
 * [delayMs] < 0 表示不启用、直接落位；同一实例只播一次，页面重建时随新实例重播。
 */
@Composable
fun Modifier.cascadeIn(
    delayMs: Long,
    startScale: Float = 0.78f,
    riseDp: Float = 10f,
): Modifier {
    var arrived by remember { mutableStateOf(delayMs < 0) }
    LaunchedEffect(delayMs) {
        if (delayMs >= 0) {
            delay(delayMs)
            arrived = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = Motion.CascadeEnter,
        label = "cascadeIn",
    )
    return graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        val scale = startScale + (1f - startScale) * progress
        scaleX = scale
        scaleY = scale
        translationY = (1f - progress) * riseDp.dp.toPx()
    }
}

/**
 * 分段关键帧求值：把单一动画进度 [progress]（0..1）映射到多通道（缩放/位移/旋转），
 * 让一套弹跳的所有通道共享同一节拍；points 按时间升序，超出两端取端点值。
 */
internal fun keyframed(progress: Float, vararg points: Pair<Float, Float>): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= points.first().first) return points.first().second
    for (i in 1 until points.size) {
        val (t, v) = points[i]
        if (p <= t) {
            val (pt, pv) = points[i - 1]
            return pv + (v - pv) * ((p - pt) / (t - pt))
        }
    }
    return points.last().second
}
