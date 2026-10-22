package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

    /**
     * 主题扩散：整屏级裁剪转场。
     * 比 [DurSlow] 略长——被裁的是一整屏旧帧，收尾要留够时间，否则圆洞边缘像被硬切。
     * 缓动必须用 [EaseReveal] 而不是 [EaseOut]，原因见 [EaseReveal]。
     */
    const val DurReveal = 560

    /**
     * 反向扩散（页面收回被点按钮）。
     * 比 [DurReveal] 短一截：收拢本来就是「东西在离开」，拖长了像卡顿；
     * 而且反向还叠了一层整块缩放，视觉信息更多，同样时长会显得慢。
     */
    const val DurRevealBack = 460

    /** 标准缓动：快速起步、缓慢收尾 */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 主题扩散专用缓动。
     *
     * 不能用 [EaseOut]：那条曲线在 50% 时间点已走完 87% 的行程，而扩散的行程是「圆半径」，
     * 半径一旦到 87% 就已经盖满整屏，剩下的时间全花在屏幕最远的那个角落里——
     * 观感上等于**瞬切**（真机实测就是这么「看不出效果」的）。
     * 这里用近似 FastOutSlowIn 的曲线：前段起速、中段匀速推进，半径到 100% 时刚好触到最远角。
     */
    val EaseReveal = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

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

    /** 底部浮岛胶囊转移形变：行进中横向拉伸 / 纵向压缩，弹性回位（液体被吸走的顿挫感） */
    val FluidStretch = spring<Float>(dampingRatio = 0.62f, stiffness = 480f)

    /** 触摸倾动回正：松手后带一次轻微过冲再落位，阻尼比低于 1 才有「纸片弹回」的手感 */
    val TiltReturn = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)

    /** 倾动高光淡入：按下即亮，快于水波纹 */
    const val DurTiltGlareIn = 90

    /** 倾动高光淡出：松手后留一点余晖，但不要拖住回正 */
    const val DurTiltGlareOut = 150
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

/**
 * 按钮统一反馈：按下回弹；触觉默认关闭。
 *
 * 只有「完成态 / 破坏性操作 / 长按」才显式传 [HapticFeedbackType]：
 * - 完成态：保存成功、导入完成、扫码识别成功；
 * - 破坏性操作：删除；
 * - 长按：长按菜单、拖拽起点。
 *
 * 普通导航、开关、选项、打开弹窗不要传 hapticType——全局每个按钮都震会让触感失去信息量。
 * 页面主体的卡片（课块、今日课程行）不用这个入口，改用 [rememberTapHaptic]。
 * 与 clickable 共用同一个 [interactionSource]，不改变水波纹。
 */
@Composable
fun Modifier.buttonFeedback(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(interactionSource, hapticType, enabled) {
        val type = hapticType
        if (!enabled || type == null) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                haptics.performHapticFeedback(type)
            }
        }
    }
    return pressScale(interactionSource, pressedScale, enabled)
}

/**
 * 卡片轻点反馈：点「整块卡片本身」时的一下轻响（课表课块、今日课程行）。
 *
 * 这类卡片是页面主体，一次点击就换出一屏详情，值得给一次触觉；但它不是「完成态」，
 * 所以用 [HapticFeedbackType.ContextClick]（轻、短）而不是 Confirm——两者混用就分不出轻重了。
 *
 * 只在 click 回调里调用：一次手势最多响一次，滚动手势取消点击时也不会误震。
 */
@Composable
fun rememberTapHaptic(type: HapticFeedbackType = HapticFeedbackType.ContextClick): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, type) {
        { haptics.performHapticFeedback(type) }
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
 * [contentPadding]：在 alpha 图层内为内容垫一圈空间。alpha<1 的离屏层以自身 bounds 为界，
 * 子项的越界绘制（选中弹跳、投影溢出）会被裁掉；需要溢出时用它撑大图层，
 * 外层布局间距自行补偿。
 * [durationMs]：单段淡入上浮的时长；切页级联可缩短以压住整体节奏。
 * [skip]：首次组合时直接落位。用于「我的」页返回时避免入口卡重播入场，
 * 保证共享元素锚点从第一帧起就可见。
 */
@Composable
fun StaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    stepMs: Long = 36,
    maxIndex: Int = 8,
    durationMs: Int = Motion.DurSlow,
    contentPadding: PaddingValues = PaddingValues(),
    skip: Boolean = false,
    content: @Composable () -> Unit,
) {
    var arrived by remember { mutableFloatStateOf(if (skip) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!skip) {
            delay(index.coerceAtMost(maxIndex) * stepMs)
            arrived = 1f
        }
    }
    val progress by animateFloatAsState(
        targetValue = arrived,
        animationSpec = tween(durationMillis = durationMs, easing = Motion.EaseOut),
        label = "staggerIn",
    )
    Box(
        modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 20.dp.toPx()
            }
            .padding(contentPadding),
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
