package com.zhijian.schedule.feature.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors

/**
 * 返回回收浮层：盖在页面与底栏之上，把快照沿一条曲线直接收进点按的入口卡。
 * 内容前段淡出，纸面颜色逐渐过渡到卡片色，最后透出下方真实的入口卡。
 *
 * 一帧的全部几何/透明度由 ProfileEntryGeometry.kt 的 [entryCollapseFrame] 推出，
 * 本文件只负责动画驱动与绘制。
 */
@Composable
fun ProfileEntryExitOverlay(
    transitions: ProfileEntryTransitions,
    captureLayer: GraphicsLayer,
) {
    val key = transitions.exitKey
    val serial = transitions.exitSerial
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val progress = remember(serial) { Animatable(0f) }
    val borderWidth = with(density) { 1.dp.toPx() }
    val cardRadius = with(density) { CARD_RADIUS_DP.dp.toPx() }
    // 路径与图层画笔复用：回收期间每帧只改参数，不新建对象
    val path = remember { Path() }
    val layerPaint = remember { Paint() }

    // 浮层常驻组合，空闲时不绘制也不拦触摸：把画布创建与首帧组合成本
    // 从「松手那一帧」挪到页面停留期间，返回起手不再因新建浮层掉帧
    LaunchedEffect(serial, key) {
        if (key == null) return@LaunchedEffect
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(PROFILE_ENTRY_EXIT_MS, easing = LinearEasing),
        )
        transitions.finishExit(serial)
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .then(
                if (key != null) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (key == null) return@Canvas
        val full = entryCaptureBounds(captureLayer.size.width, captureLayer.size.height, size)
        val target = transitions.cardBounds(key) ?: entryExitFallbackTarget(size)

        val frame = entryCollapseFrame(full, target, progress.value, cardRadius)
        val fillColor = lerp(colors.bg, colors.surface, frame.fillMix)

        path.rewind()
        path.addRoundRect(RoundRect(frame.container, CornerRadius(frame.radius)))
        drawPath(path, fillColor.copy(alpha = frame.fillAlpha))

        if (frame.contentAlpha > 0.001f) {
            if (frame.contentAlpha < 0.999f) {
                clipPath(path) {
                    layerPaint.alpha = frame.contentAlpha
                    drawContext.canvas.saveLayer(frame.container, layerPaint)
                    withTransform({
                        translate(frame.translationX, frame.translationY)
                        scale(frame.coverScale, frame.coverScale, pivot = Offset.Zero)
                    }) {
                        drawLayer(captureLayer)
                    }
                    drawContext.canvas.restore()
                }
            } else {
                // 前段内容仍不透明：省掉整屏 saveLayer，起手帧不额外分配大图层
                clipPath(path) {
                    withTransform({
                        translate(frame.translationX, frame.translationY)
                        scale(frame.coverScale, frame.coverScale, pivot = Offset.Zero)
                    }) {
                        drawLayer(captureLayer)
                    }
                }
            }
        }

        if (frame.borderAlpha > 0.001f) {
            drawPath(
                path = path,
                color = colors.border.copy(alpha = frame.borderAlpha),
                style = Stroke(borderWidth),
            )
        }
    }
}
