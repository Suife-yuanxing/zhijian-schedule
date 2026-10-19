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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.glass.GlassBackdropState
import com.zhijian.schedule.core.ui.glass.glassBackdropSurface
import com.zhijian.schedule.core.ui.glass.glassDroplet
import com.zhijian.schedule.core.ui.glass.glassSurface
import com.zhijian.schedule.core.ui.keyframed

internal data class TabItem(
    val route: String,
    /** tab 图根页路由：区分「已在根页」（复点只弹跳）与「在二级页」（先回根页） */
    val rootRoute: String,
    val label: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
)

// ---------- 灵动岛式底部栏：悬浮纸感胶囊 + 弹簧滑动选中胶囊 ----------

private val IslandShape = RoundedCornerShape(percent = 50)
private val IslandHeight = 64.dp
private val IndicatorInsetX = 10.dp
private val IndicatorInsetY = 8.dp

/** 根页底部留白：浮岛悬浮于内容之上，页面内容需为其让出的总高度（含留白与呼吸） */
internal val IslandContentPadding = 96.dp

/**
 * 底部浮岛：整条胶囊两侧留白悬浮在页面之上，选中胶囊在 tab 间弹簧滑动（流体转移）。
 * 只负责容器与滑动指示器，条目由 [content] 提供（图标交互与改造前一致）。
 */
@Composable
internal fun FloatingIslandBar(
    selectedIndex: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    clock: State<Float>? = null,
    tilt: State<Offset>? = null,
    backdrop: GlassBackdropState? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalCourseColors.current
    val glass = LocalCoursePreset.current.glass
    val isDark = LocalIsDark.current
    // 流动时钟由 AppRoot 按主题提供（与背景录制层共用同一拍）；其余主题为 null，零开销
    val glassClock = if (glass != null) clock else null
    // 胶囊本体的窗口位置：折射采样原点必须用它（外框含内边距，直接用会有数十像素错位）
    var pillOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
    ) {
        val itemWidth = maxWidth / itemCount
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = Motion.FluidSlide,
            label = "islandIndicator",
        )
        // 流体转移：切换瞬间胶囊横向拉伸、纵向压缩，随滑动弹簧回位——像液体被吸去下一格
        val stretch = remember { Animatable(1f) }
        var transferSettled by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex) {
            if (!transferSettled) {
                transferSettled = true
                return@LaunchedEffect
            }
            stretch.snapTo(0f)
            stretch.animateTo(1f, Motion.FluidStretch)
        }
        val bodyAlpha = glass?.let { if (isDark) it.bodyAlphaDark else it.bodyAlphaLight } ?: 0f
        val glassTint = glass?.let { if (isDark) it.tintDark else it.tintLight } ?: Color.Transparent
        val chipAlpha = glass?.let { if (isDark) it.chipAlphaDark else it.chipAlphaLight } ?: 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(IslandHeight)
                .onGloballyPositioned { pillOriginInWindow = it.boundsInWindow().topLeft }
                // 投影调轻：黑影透进半透明玻璃会把水体压成灰雾；玻璃模式主体近清水，靠投影与边缘高光勾勒浮岛轮廓
                .shadow(
                    elevation = if (glass != null) 8.dp else 4.dp,
                    shape = IslandShape,
                    ambientColor = Color.Black.copy(alpha = if (glass != null) 0.14f else 0.08f),
                    spotColor = Color.Black.copy(alpha = if (glass != null) 0.34f else 0.20f),
                )
                .then(
                    when {
                        glass == null -> Modifier
                            .clip(IslandShape)
                            .background(colors.surface)
                            .border(1.dp, colors.border, IslandShape)
                        backdrop != null -> Modifier.glassBackdropSurface(
                            shape = IslandShape,
                            tint = glassTint,
                            bodyAlpha = bodyAlpha,
                            clock = glassClock,
                            tilt = tilt,
                            backdrop = backdrop,
                            originInLayer = { pillOriginInWindow - backdrop.contentOriginInWindow },
                        )
                        else -> Modifier.glassSurface(
                            shape = IslandShape,
                            tint = glassTint,
                            bodyAlpha = bodyAlpha,
                            clock = glassClock,
                            tilt = tilt,
                        )
                    },
                ),
        ) {
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .padding(horizontal = IndicatorInsetX, vertical = IndicatorInsetY)
                    .width(itemWidth - IndicatorInsetX * 2)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val k = 1f - stretch.value
                        scaleX = 1f + 0.10f * k
                        scaleY = 1f - 0.07f * k
                    }
                    .then(
                        if (glass != null) {
                            Modifier
                                .glassDroplet(
                                    shape = IslandShape,
                                    tint = colors.accent,
                                    alpha = chipAlpha,
                                    clock = glassClock,
                                )
                                .liquidRipple(IslandShape, selectedIndex)
                        } else {
                            Modifier
                                .clip(IslandShape)
                                .background(colors.accent.copy(alpha = 0.13f))
                        },
                    ),
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** 浮岛条目：图标交互沿用原有行为（按压蓄势 + 果冻弹跳 + 描边实心切换） */
@Composable
internal fun RowScope.IslandTabItem(
    tab: TabItem,
    selected: Boolean,
    bounceTrigger: Int,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val haptics = LocalHapticFeedback.current
    // 玻璃主题下选中项落在饱和水色液滴上：图标文字取 onAccent（亮=白 / 暗=深蓝），与液滴底色形成高对比
    val selectedColor = if (LocalCoursePreset.current.glass != null) colors.onAccent else colors.text
    val pressSource = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue = if (selected) selectedColor else colors.textMuted,
        // 与胶囊滑动同拍渐变，避免图标先行变色、胶囊还在路上的割裂感
        animationSpec = tween(Motion.DurMedium, easing = Motion.EaseOut),
        label = "tabContentColor",
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = pressSource,
                // 白色浮岛上涟漪会变成一块浅灰矩形"虚影"，触感反馈交给图标按压+弹跳
                indication = null,
                role = Role.Tab,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * 液滴转移时的水波：从胶囊中心扩散一圈柔白光环并淡出（随 [trigger] 触发，仅玻璃主题）。
 * 静息零开销：动画跑完立即停笔，不产生逐帧重绘。
 */
@Composable
private fun Modifier.liquidRipple(shape: RoundedCornerShape, trigger: Int): Modifier {
    val ripple = remember { Animatable(1f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(460, easing = Motion.EaseOut))
    }
    return drawWithCache {
        val r = shape.topStart.toPx(size, this)
        val cr = CornerRadius(r, r)
        val pill = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, cr, cr))
        }
        val center = Offset(size.width / 2f, size.height / 2f)
        val startR = size.width * 0.16f
        val endR = size.width * 0.88f
        onDrawBehind {
            val p = ripple.value
            if (p >= 1f) return@onDrawBehind
            val radius = startR + (endR - startR) * p
            val fade = 1f - p
            clipPath(pill) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.62f to Color.White.copy(alpha = 0.24f * fade * fade),
                            1f to Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}

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
