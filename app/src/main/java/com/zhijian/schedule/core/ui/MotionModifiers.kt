package com.zhijian.schedule.core.ui
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
fun Modifier.selectedPop(
    selected: Boolean,
    popScale: Float = 1.06f,
): Modifier {
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
    durationMs: Int = Motion.DUR_SLOW,
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
            }.padding(contentPadding),
    ) {
        content()
    }
}

/**
 * 级联入场：延迟 [delayMs] 后淡入 + 弹簧放大 + 自下而上落位（首次进入课表时课块逐个涌入）。
 *
 * [delayMs] < 0 表示**不启用**、首帧直接落位。课表页用它表达「这次切页不播级联」：
 * 之前每次进入都重播，每切一次 tab 整屏课块都要从 alpha=0 按阅读顺序重长一遍（整轮封顶
 * 700ms），而切页滑动只要约 230ms——读起来就是「这一页刷新了一遍、内容从左上角摊开」。
 *
 * 历史（保留是因为别再踩）：这里一度有个 `restartKey`，用来在「转场复用内容槽、
 * 页面没离开组合」时强推重播（navigation-compose 按 back stack entry 的 **id** 复用内容槽，
 * `saveState`/`restoreState` 恢复的 entry 还是同一个 id，所以 `remember` 与
 * `LaunchedEffect(delayMs)` 都不会重跑）。那个 key 解决了「快切没有入场」，代价是
 * 「每次切页都刷新一遍」。现在改成宿主闸门控制「播不播」，key 就没有存在必要了。
 *
 * 不能反过来「把 target 拉回 0 再拉回 1」：那样会先播一段反向收缩，块会缩回去再长出来。
 */
@Composable
fun Modifier.cascadeIn(
    delayMs: Long,
    startScale: Float = 0.78f,
    riseDp: Float = 10f,
): Modifier {
    val progress = remember { Animatable(if (delayMs < 0) 1f else 0f) }
    LaunchedEffect(delayMs) {
        if (delayMs < 0) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // 已经落位就不重播：延迟值随课表数据变化（增删课会改序号 → 改延迟）时，
        // 重播会把一整屏课块重新洗一遍，观感与「切页刷新」同源。
        if (progress.value >= 1f) return@LaunchedEffect
        delay(delayMs)
        progress.animateTo(1f, Motion.CascadeEnter)
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val scale = startScale + (1f - startScale) * p
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * riseDp.dp.toPx()
    }
}
