package com.zhijian.schedule.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.theme.LocalCourseColors
import kotlin.math.max
import kotlin.math.min

/** 「我的」页入口卡与二级页共享的 key；AppRoot 与 ProfileScreen 必须使用同一组常量 */
object ProfileEntryKeys {
    const val IMPORT = "profile-entry-import"
    const val GRADES = "profile-entry-grades"
    const val GRADES_GPA = "profile-entry-grades-gpa"
    const val SETTINGS = "profile-entry-settings"
}

/** 进入：卡片实时展开到整页 */
const val PROFILE_ENTRY_ENTER_MS = 480

/** 返回：整页直接收进入口卡；略快于进入，保证返回干脆 */
const val PROFILE_ENTRY_EXIT_MS = 400

/** 进入期间「我的」页保持原位、不提前销毁，给实时展开当背景 */
const val PROFILE_ENTRY_HOLD_MS = PROFILE_ENTRY_ENTER_MS + 80

private val EntryEasing = CubicBezierEasing(0.22f, 0f, 0f, 1f)
private const val CARD_RADIUS_DP = 14f

/** 收拢进度：起手快、尾声长，纸面先迅速贴近卡片轮廓，再软着陆 */
private fun collapseProgress(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}

@Stable
class ProfileEntryTransitions {
    private val cardWindowBounds = mutableStateMapOf<String, Rect>()

    /** 内容容器（NavHost 所在区域）在窗口中的位置，同时给出浮层与页面共用的像素坐标原点 */
    var contentBounds by mutableStateOf(Rect.Zero)
        private set

    /** 正在返回的入口 key；非空期间显示回收浮层 */
    var exitKey by mutableStateOf<String?>(null)
        private set
    var exitSerial by mutableIntStateOf(0)
        private set

    fun updateContentBounds(bounds: Rect) {
        if (contentBounds != bounds) contentBounds = bounds
    }

    fun updateCardBounds(key: String, bounds: Rect) {
        if (cardWindowBounds[key] != bounds) cardWindowBounds[key] = bounds
    }

    /** 入口卡在内容区域内的像素坐标；页面根与回收浮层共用这一坐标系 */
    fun cardBounds(key: String): Rect? {
        val card = cardWindowBounds[key] ?: return null
        val origin = contentBounds.topLeft
        return Rect(
            left = card.left - origin.x,
            top = card.top - origin.y,
            right = card.right - origin.x,
            bottom = card.bottom - origin.y,
        )
    }

    fun beginExit(key: String) {
        if (exitKey == null) {
            exitKey = key
            exitSerial++
        }
    }

    fun finishExit(serial: Int) {
        if (serial != exitSerial) return
        exitKey = null
    }
}

/** 入口卡侧：上报位置，作为返回时的落点 */
@Composable
fun ProfileEntryAnchor(
    key: String,
    transitions: ProfileEntryTransitions,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            transitions.updateCardBounds(key, coordinates.boundsInWindow())
        }
    ) {
        content()
    }
}

/**
 * 二级页容器。
 *
 * 进入：页面仍是实时的，按「卡片窗口 → 整页」的裁剪框展开，内容按 cover 比例缩放，
 * 所以从卡片里看到的是页面中段而不是空白。
 *
 * 返回时只在这两帧把内容录进 [captureLayer]（GraphicsLayer，无位图读回），
 * 随后交给 AppRoot 顶层浮层直接收进入口卡。平时不录制，
 * 避免 AndroidView（如扫码页 CameraX 预览）被录制层遮挡。
 */
@Composable
fun ProfileEntryPage(
    entryKey: String,
    transitions: ProfileEntryTransitions,
    captureLayer: GraphicsLayer,
    onPop: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    var forwarded by rememberSaveable { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var captureRequested by remember { mutableStateOf(false) }

    val enter = remember { Animatable(if (forwarded) 1f else 0f) }
    val contentBounds = transitions.contentBounds
    val cardRect = transitions.cardBounds(entryKey)
    val contentSize = contentBounds.size
    val ready = contentSize.width > 0f && contentSize.height > 0f && cardRect != null

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle()
    val isTop = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val requestExit: () -> Unit = {
        if (forwarded && !exiting) {
            exiting = true
            captureRequested = true
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
        // 等两帧，确保图层已记录完整页面；浮层先上屏，再执行 pop
        withFrameNanos { }
        withFrameNanos { }
        transitions.beginExit(entryKey)
        onPop()
    }

    BackHandler(enabled = isTop) { requestExit() }

    // 内容始终挂在同一个 Box 下：进入结束后只移除修饰符，页面不会因换槽而重建
    if (forwarded || ready) {
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
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(Modifier.fillMaxSize().background(colors.bg))
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (captureRequested) {
                            captureLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(captureLayer)
                        } else {
                            drawContent()
                        }
                    }
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
                        }
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
): Modifier = this
    .drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 0.999f) {
            val container = lerpRect(cardRect, Rect(Offset.Zero, contentSize), p)
            val radius = lerpFloat(cardRadius, 0f, p)
                .coerceAtMost(min(container.width, container.height) / 2f)
            drawRoundRect(
                color = borderColor.copy(alpha = 1f - p),
                topLeft = container.topLeft,
                size = container.size,
                cornerRadius = CornerRadius(radius),
                style = Stroke(borderWidth),
            )
        }
    }
    .graphicsLayer {
        val p = progress.value
        val container = lerpRect(cardRect, Rect(Offset.Zero, contentSize), p)
        val cover = max(
            container.width / contentSize.width,
            container.height / contentSize.height,
        ).coerceAtLeast(0.01f)
        val tx = container.center.x - contentSize.width * cover / 2f
        val ty = container.center.y - contentSize.height * cover / 2f
        val localClip = Rect(
            left = (container.left - tx) / cover,
            top = (container.top - ty) / cover,
            right = (container.right - tx) / cover,
            bottom = (container.bottom - ty) / cover,
        )
        val radius = (lerpFloat(cardRadius, 0f, p) / cover)
            .coerceAtMost(min(localClip.width, localClip.height) / 2f)

        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = cover
        scaleY = cover
        translationX = tx
        translationY = ty
        clip = true
        shape = ContainerShape(localClip, radius)
    }

/**
 * 返回回收浮层：盖在页面与底栏之上，把快照沿一条曲线直接收进点按的入口卡。
 * 内容前段淡出，纸面颜色逐渐过渡到卡片色，最后透出下方真实的入口卡。
 */
@Composable
fun ProfileEntryExitOverlay(
    transitions: ProfileEntryTransitions,
    captureLayer: GraphicsLayer,
) {
    val key = transitions.exitKey ?: return
    val serial = transitions.exitSerial
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val progress = remember(serial) { Animatable(0f) }
    val borderWidth = with(density) { 1.dp.toPx() }
    val cardRadius = with(density) { CARD_RADIUS_DP.dp.toPx() }

    LaunchedEffect(serial) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(PROFILE_ENTRY_EXIT_MS, easing = LinearEasing),
        )
        transitions.finishExit(serial)
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        val layerSize = captureLayer.size
        val w = layerSize.width.toFloat().takeIf { it > 0f } ?: size.width
        val h = layerSize.height.toFloat().takeIf { it > 0f } ?: size.height
        val full = Rect(0f, 0f, w, h)
        val target = transitions.cardBounds(key) ?: Rect(
            left = size.width * 0.1f,
            top = size.height * 0.58f,
            right = size.width * 0.9f,
            bottom = size.height * 0.64f,
        )

        val p = progress.value
        val move = collapseProgress(p)
        val container = lerpRect(full, target, move)
        val radius = (cardRadius * smoothstep(0f, 0.3f, move))
            .coerceAtMost(min(container.width, container.height) / 2f)

        val contentAlpha = 1f - smoothstep(0.16f, 0.52f, p)
        val fillAlpha = 1f - smoothstep(0.72f, 0.98f, p)
        val borderAlpha = smoothstep(0.02f, 0.12f, p) *
            (1f - smoothstep(0.55f, 0.85f, p))
        val fillColor = lerp(colors.bg, colors.surface, smoothstep(0.15f, 0.6f, move))

        val path = Path().apply {
            addRoundRect(RoundRect(container, CornerRadius(radius)))
        }
        drawPath(path, fillColor.copy(alpha = fillAlpha))

        if (contentAlpha > 0.001f) {
            val cover = max(container.width / w, container.height / h).coerceAtLeast(0.01f)
            val dstW = w * cover
            val dstH = h * cover
            val tx = container.center.x - dstW / 2f
            val ty = container.center.y - dstH / 2f
            clipPath(path) {
                drawContext.canvas.saveLayer(
                    container,
                    Paint().apply { alpha = contentAlpha },
                )
                withTransform({
                    translate(tx, ty)
                    scale(cover, cover, pivot = Offset.Zero)
                }) {
                    drawLayer(captureLayer)
                }
                drawContext.canvas.restore()
            }
        }

        if (borderAlpha > 0.001f) {
            drawPath(
                path = path,
                color = colors.border.copy(alpha = borderAlpha),
                style = Stroke(borderWidth),
            )
        }
    }
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

private fun lerpRect(start: Rect, target: Rect, fraction: Float): Rect = Rect(
    left = lerpFloat(start.left, target.left, fraction),
    top = lerpFloat(start.top, target.top, fraction),
    right = lerpFloat(start.right, target.right, fraction),
    bottom = lerpFloat(start.bottom, target.bottom, fraction),
)

private fun lerpFloat(start: Float, target: Float, fraction: Float): Float =
    start + (target - start) * fraction

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge0 == edge1) return if (value < edge0) 0f else 1f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
