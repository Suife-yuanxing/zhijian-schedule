package com.zhijian.schedule.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.ui.glass.glassDroplet
import com.zhijian.schedule.core.ui.island.IslandPalette
import com.zhijian.schedule.core.ui.island.IslandTokens
import kotlin.math.roundToInt

/**
 * 选中胶囊的图层：位置与尺寸都由 `islandIndicatorRect` 在**布局阶段**求解
 * （只失效布局、不触发重组），圆角恒等于高的一半，所以果冻拉伸时两端仍是正半圆，
 * 不会被 scaleX 拉成椭圆。
 *
 * 形变读的是**行程进度**：由 [slide] 的实时值在 [travelFrom]..[travelTo] 之间反算。
 * 反算而不是另存一个进度值，是因为「同一条时间线」不该靠对齐来保证——
 * 位移停下的那一帧，进度必然恰好是 1，形变必然恰好归位。
 *
 * 方向同样由 [travelFrom]/[travelTo] 给出（`to - from` 的符号），不读 `slide.velocity`：
 * 停稳瞬间速度归零、符号会丢，而一次行程的方向从头到尾不变。
 *
 * 玻璃主题下胶囊是一颗水滴（[IslandIndicatorSkin.Glass]）；其余主题用「浅投影 + 自绘胶囊」
 * 把「选中」从纯色块推进到层次（[IslandIndicatorSkin.Paper]）。
 */
@Composable
internal fun IslandIndicator(
    slide: Animatable<Float, AnimationVector1D>,
    travelFrom: State<Float>,
    travelTo: State<Float>,
    itemWidth: Dp,
    skin: IslandIndicatorSkin,
) {
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val barWidth = constraints.maxWidth
                val barHeightPx = constraints.maxHeight
                val rect =
                    islandIndicatorRect(
                        metrics =
                            IslandBarMetrics(
                                barWidthPx = barWidth.toFloat(),
                                barHeightPx = barHeightPx.toFloat(),
                                itemWidthPx = itemWidth.toPx(),
                                insetXPx = IndicatorInsetX.toPx(),
                                insetYPx = IndicatorInsetY.toPx(),
                            ),
                        slide = slide.value,
                        from = travelFrom.value,
                        to = travelTo.value,
                    )
                val w = rect.width.roundToInt().coerceAtLeast(1)
                val h = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(barWidth, barHeightPx) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }.then(skin.modifier()),
    )
}

@Immutable
internal sealed interface IslandIndicatorSkin {
    /** [glassDroplet] 是 @Composable 的 Modifier 工厂（要读组合期的时钟），所以这里跟着标 */
    @Composable
    fun modifier(): Modifier

    /** 玻璃档：胶囊是一颗水滴（折射 + 高光），本身就不再描边 */
    data class Glass(
        val tint: Color,
        val alpha: Float,
        val clock: State<Float>?,
    ) : IslandIndicatorSkin {
        @Composable
        override fun modifier(): Modifier =
            Modifier.glassDroplet(
                shape = IslandShape,
                tint = tint,
                alpha = alpha,
                clock = clock,
            )
    }

    /** 纸面档：浅投影 + 自绘渐变胶囊（顶受光、底收柔） */
    data class Paper(
        val palette: IslandPalette,
    ) : IslandIndicatorSkin {
        @Composable
        override fun modifier(): Modifier =
            Modifier
                // 选中胶囊离纸面一层：淡胶囊与浮岛底只有 1.14:1，纯靠色差读不出
                // 「浮在上面」；一层浅投影把「选中」从色块推进到层次
                .shadow(
                    elevation = 4.dp,
                    shape = IslandShape,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.14f),
                ).clip(IslandShape)
                .drawBehind { drawIslandPill(palette) }
    }
}

/**
 * 画选中胶囊：竖向渐变填充 + 顶亮底柔的描边，圆角恒取 高/2（真胶囊，不是椭圆）。
 *
 * 为什么要自己画而不是 `background` + `border`：胶囊的尺寸每帧都在变（果冻拉伸），
 * 而 `border` 只能画均匀的一圈；拆成「顶受光、底收柔」之后，这一圈读作一颗有厚度的
 * 水滴，而不是贴上去的描边框。
 *
 * 描边本身不能省：淡胶囊与浮岛底同色系（纸本暖白实测仅 1.14:1），边界会消失，
 * 没有这条 hairline，「胶囊在往哪走」就没有参照物。
 */
private fun DrawScope.drawIslandPill(island: IslandPalette) {
    val radius = CornerRadius(size.height / 2f)
    drawRoundRect(
        brush =
            Brush.verticalGradient(
                listOf(
                    island.chip.copy(alpha = IslandTokens.CHIP_ALPHA * IslandTokens.CHIP_FILL_TOP),
                    island.chip.copy(alpha = IslandTokens.CHIP_ALPHA * IslandTokens.CHIP_FILL_BOTTOM),
                ),
                0f,
                size.height,
            ),
        cornerRadius = radius,
    )
    val stroke = 1.dp.toPx()
    drawRoundRect(
        brush =
            Brush.verticalGradient(
                listOf(
                    island.chip.copy(alpha = island.chipEdgeAlpha * IslandTokens.CHIP_EDGE_TOP),
                    island.chip.copy(alpha = island.chipEdgeAlpha * IslandTokens.CHIP_EDGE_BOTTOM),
                ),
                stroke,
                (size.height - stroke).coerceAtLeast(stroke),
            ),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((size.height - stroke) / 2f),
        style = Stroke(stroke),
    )
}
