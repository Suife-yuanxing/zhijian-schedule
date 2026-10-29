package com.zhijian.schedule.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import kotlin.math.min

/**
 * 二级页容器。
 *
 * 进入：页面仍是实时的，按「卡片窗口 → 整页」的裁剪框展开，内容按 cover 比例缩放，
 * 所以从卡片里看到的是页面中段而不是空白。
 *
 * 返回时只在这两帧把内容录进 [captureLayer]（GraphicsLayer，无位图读回），
 * 随后交给 AppRoot 顶层浮层直接收进入口卡。平时不录制，
 * 避免 AndroidView（如扫码页 CameraX 预览）被录制层遮挡。
 *
 * 逐帧像素由 ProfileEntryGeometry.kt 的纯函数给出，本文件只负责状态机与接线。
 */
@Composable
fun ProfileEntryPage(
    entryKey: String,
    transitions: ProfileEntryTransitions,
    captureLayer: GraphicsLayer,
    isTop: Boolean,
    onPop: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    var forwarded by rememberSaveable { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var captureRequested by remember { mutableStateOf(false) }
    var abortRequested by remember { mutableStateOf(false) }
    // 已交棒给入口卡：页面随即不可见。返回发生在展开动画中途时，压栈转场还没结束，
    // 被弹出的页面仍会被 NavHost 留在组合里画到转场结束，若不撤掉内容，
    // 卡片位置会残留一段二级页画面，并挡住返回后「我的」页的点击
    var dismissed by remember { mutableStateOf(false) }
    // 整页只需录一次：第二帧直接重绘图层，避免松手瞬间把整页显示列表重放两遍
    val captureState = remember { CaptureState() }

    val enter = remember { Animatable(if (forwarded) 1f else 0f) }
    val contentBounds = transitions.contentBounds
    val cardRect = transitions.cardBounds(entryKey)
    val contentSize = contentBounds.size
    val ready = contentSize.width > 0f && contentSize.height > 0f && cardRect != null

    val requestExit: () -> Unit = {
        if (!exiting) {
            exiting = true
            // 打开动画还没走完时没有整页快照可回收，改走反向收回，返回键不会空转
            if (forwarded) captureRequested = true else abortRequested = true
        }
    }

    LaunchedEffect(forwarded, ready) {
        if (forwarded) {
            enter.snapTo(1f)
            return@LaunchedEffect
        }
        if (!ready) {
            withFrameNanos { }
            val containerBounds = transitions.contentBounds
            if (containerBounds.width <= 0f || transitions.cardBounds(entryKey) == null) {
                forwarded = true
            }
            return@LaunchedEffect
        }
        enter.snapTo(0f)
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(PROFILE_ENTRY_ENTER_MS, easing = EntryEasing),
        )
        forwarded = true
    }

    LaunchedEffect(captureRequested) {
        if (!captureRequested) return@LaunchedEffect
        // 等录制完成那一帧结束即可：图层已含整页，浮层下一帧直接重绘它，
        // 不再多等一帧，把返回起手前的停顿压到最短（录制异常时最多等 4 帧）
        var waits = 0
        while (!captureState.recorded && waits < 4) {
            withFrameNanos { }
            waits++
        }
        if (!captureState.recorded) {
            // 极端情况下没录到整页：直接返回，宁可少一段回收动画也不放空白纸面
            dismissed = true
            onPop()
            return@LaunchedEffect
        }
        transitions.beginExit(entryKey)
        dismissed = true
        onPop()
    }

    // 展开中途返回：按当前进度反向收回再退出。时长按剩余距离缩放，
    // 并保证收拢结束仍落在「我的」页的保持窗口内，返回全程有连续画面
    LaunchedEffect(abortRequested) {
        if (!abortRequested) return@LaunchedEffect
        if (forwarded) {
            // 恰好赶在动画收尾：页面上已是整页，改走整页快照回收
            captureRequested = true
            return@LaunchedEffect
        }
        if (ready) {
            val remaining =
                (PROFILE_ENTRY_ABORT_MS * (1f - enter.value))
                    .toInt()
                    .coerceIn(PROFILE_ENTRY_ABORT_MIN_MS, PROFILE_ENTRY_ABORT_MS)
            enter.animateTo(
                targetValue = 0f,
                animationSpec = tween(remaining, easing = EntryEasing),
            )
        }
        // 收拢结束即交给真实入口卡，撤掉页面内容，避免转场未收尾时残留画面
        dismissed = true
        onPop()
    }

    // 顶层由路由判断：展开/收拢全程都能接到返回，被上层页覆盖时自动让位
    BackHandler(enabled = isTop || !forwarded) { requestExit() }

    // 内容始终挂在同一个 Box 下：进入结束后只移除修饰符，页面不会因换槽而重建
    if ((forwarded || ready) && !dismissed) {
        val colors = LocalCourseColors.current
        val density = LocalDensity.current
        val forwardActive = !forwarded && ready
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (forwardActive) {
                        Modifier.profileEntryForward(
                            progress = enter,
                            cardRect = cardRect!!,
                            contentSize = contentSize,
                            borderColor = colors.border,
                            borderWidth = with(density) { 1.dp.toPx() },
                            cardRadius = with(density) { CARD_RADIUS_DP.dp.toPx() },
                            // 收回末段让整页内容融回入口卡，避免收拢到位的一瞬硬切
                            fadeToCard = exiting,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(Modifier.fillMaxSize().background(colors.bg))
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (captureRequested) {
                            if (!captureState.recorded) {
                                captureLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                captureState.recorded = true
                            }
                            drawLayer(captureLayer)
                        } else {
                            drawContent()
                        }
                    },
            ) {
                content(requestExit)
            }
            // 展开期间页面尚未完整呈现，拦掉点击：入口卡上的连点不应穿透到
            // 刚推入的二级页，误触其中的列表/弹窗
            if (!forwarded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }
    }
}

/**
 * 进入动画只改绘制/图层，不读取进度到组合阶段：内容不会逐帧重组。
 * 图层先在整页坐标里按 cover 缩放对齐裁剪框，再把裁剪框反算回内容坐标，
 * 这样同一个 graphicsLayer 就能同时完成“窗口展开”和“内容等比缩放”。
 */
private fun Modifier.profileEntryForward(
    progress: Animatable<Float, *>,
    cardRect: Rect,
    contentSize: Size,
    borderColor: Color,
    borderWidth: Float,
    cardRadius: Float,
    fadeToCard: Boolean,
): Modifier =
    this
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p < 0.999f) {
                val container = entryForwardContainer(cardRect, contentSize, p)
                // 收回末段边框也要一起化掉，否则内容已消失、卡片位置还留一圈描边
                val borderAlpha = (1f - p) * cardHandOffAlpha(p, fadeToCard)
                drawRoundRect(
                    color = borderColor.copy(alpha = borderAlpha),
                    topLeft = container.topLeft,
                    size = container.size,
                    cornerRadius = CornerRadius(entryForwardRadius(cardRect, contentSize, p, cardRadius)),
                    style = Stroke(borderWidth),
                )
            }
        }.graphicsLayer {
            val p = progress.value
            val transform = entryCoverTransform(cardRect, contentSize, p, cardRadius)
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.translationX
            translationY = transform.translationY
            // 卡片形状下 cover≈1，内容几乎不再缩小；收回末段淡出，把卡片上的画面交还给真实入口卡
            alpha = cardHandOffAlpha(p, fadeToCard)
            clip = true
            shape = ContainerShape(transform.clipRect, transform.radius)
        }

/** 一次性录制标记：普通可变字段，绘制阶段写入不会触发额外重组 */
private class CaptureState {
    var recorded = false
}

private class ContainerShape(
    private val clipRect: Rect,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = radius.coerceIn(0f, min(clipRect.width, clipRect.height) / 2f)
        return Outline.Rounded(RoundRect(clipRect, CornerRadius(r)))
    }
}
