package com.zhijian.schedule.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.glass.GlassBackdropState
import com.zhijian.schedule.core.ui.glass.glassBackdropSurface
import com.zhijian.schedule.core.ui.glass.glassSurface
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.IslandTokens
import com.zhijian.schedule.core.ui.rememberAnimationsDisabled
import com.zhijian.schedule.core.ui.island.rememberIslandPalette
import kotlin.math.abs

internal data class TabItem(
    val route: String,
    /** tab 图根页路由：区分「已在根页」（复点只弹跳）与「在二级页」（先回根页） */
    val rootRoute: String,
    val label: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
)

// ---------- 灵动岛式底部栏：悬浮纸感胶囊 + 弹簧滑动选中胶囊 ----------

internal val IslandShape = RoundedCornerShape(percent = 50)

/** 展开态高度：与改造前一致 */
internal val IslandHeightExpanded = 64.dp

/** 收敛态高度：仍高于 48dp 触控下限，收的是「体量」不是「命中区」 */
internal val IslandHeightCollapsed = 48.dp

internal val IslandInsetXExpanded = 18.dp
internal val IslandInsetXCollapsed = 48.dp
internal val IndicatorInsetX = 10.dp
internal val IndicatorInsetY = 8.dp

/** 根页底部留白：浮岛悬浮于内容之上，页面内容需为其让出的总高度（含留白与呼吸） */
internal val IslandContentPadding = 96.dp

/** 收敛态留白：浮岛变矮后内容可以多让出一点，但保留手势条呼吸空间 */
internal val IslandContentPaddingCollapsed = 78.dp

/** 按收敛进度插值的底部留白 */
internal fun islandContentPadding(collapse: Float): Dp {
    val t = collapse.coerceIn(0f, 1f)
    return IslandContentPadding + (IslandContentPaddingCollapsed - IslandContentPadding) * t
}

/**
 * 底部浮岛：整条胶囊两侧留白悬浮在页面之上，选中胶囊在 tab 间弹簧滑动（流体转移）。
 * 只负责容器与滑动指示器，条目由 [content] 提供（图标交互与改造前一致）。
 *
 * 尺寸随收敛进度插值（见 [islandBarSideInset] / [islandBarHeight]），
 * 指示器本体在 [IslandIndicator] 里，条目在 IslandTabItem.kt 里。
 */
@Composable
internal fun FloatingIslandBar(
    selectedIndex: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    clock: State<Float>? = null,
    tilt: State<Offset>? = null,
    backdrop: GlassBackdropState? = null,
    collapse: IslandCollapseState? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalCourseColors.current
    val glass = LocalCoursePreset.current.glass
    val isDark = LocalIsDark.current
    val island = rememberIslandPalette()
    // 流动时钟由 AppRoot 按主题提供（与背景录制层共用同一拍）；其余主题为 null，零开销
    val glassClock = if (glass != null) clock else null
    // 胶囊本体的窗口位置：折射采样原点必须用它（外框含内边距，直接用会有数十像素错位）
    var pillOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    // 收敛进度：不可收敛的场景（大屏导轨、未接入状态）恒为 0，所有插值退化为原尺寸。
    // 用 derivedStateOf 量化到 1/16：animated 是逐帧变动的 Animatable，直接在组合期读它，
    // 整条浮岛（含 3 个条目、每个条目的颜色/标签高度/图标弹跳动画）会跟着每帧重组一次。
    // 量化后一次收敛动画最多重组 16 次，单步 ≈1.1dp（18→48dp）/ 1dp（64→48dp），肉眼读不出台阶。
    val collapseProgress by remember(collapse) {
        derivedStateOf { quantizeCollapseProgress(collapse?.animated?.value ?: 0f) }
    }
    val sideInset = islandBarSideInset(collapseProgress)
    val barHeight = islandBarHeight(collapseProgress)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = sideInset, end = sideInset, top = 6.dp, bottom = 10.dp),
    ) {
        val itemWidth = maxWidth / itemCount
        // 系统关掉动画时直接落位（辅助功能「移除动画」/ 开发选项动画缩放 0）
        val animationsOff = rememberAnimationsDisabled()
        // 选中胶囊的位置以 **tab 下标** 为单位做动画：速度单位随之变成「每秒走几个 tab」，
        // 与屏宽、tab 数无关，果冻拉伸才在不同屏幕上一致（推导见 Motion.FluidSlide）
        val slide = remember { Animatable(selectedIndex.toFloat()) }
        // 本次动画跨过的 tab 数：速度按「相对跨度」折算，跨 2 格不该一路顶在满拉伸。
        // 用 State 而不是普通变量：它要在**布局阶段**被读取（见 IslandIndicator），
        // 状态读留在测量里才能只失效布局、不连带重组整条浮岛。
        val travel = remember { mutableFloatStateOf(1f) }
        LaunchedEffect(selectedIndex, animationsOff) {
            val target = selectedIndex.toFloat()
            if (animationsOff) {
                slide.snapTo(target)
            } else {
                travel.floatValue = abs(target - slide.value).coerceAtLeast(1f)
                slide.animateTo(target, Motion.FluidSlide)
            }
        }
        val bodyAlpha = glass?.let { if (isDark) it.bodyAlphaDark else it.bodyAlphaLight } ?: 0f
        val glassTint = glass?.let { if (isDark) it.tintDark else it.tintLight } ?: Color.Transparent
        val chipAlpha = if (glass != null) island.chipAlpha else IslandTokens.CHIP_ALPHA
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
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
            IslandIndicator(
                slide = slide,
                travel = travel,
                itemWidth = itemWidth,
                island = island,
                chipAlpha = chipAlpha,
                glassActive = glass != null,
                glassClock = glassClock,
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
