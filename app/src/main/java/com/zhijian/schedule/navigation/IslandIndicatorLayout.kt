package com.zhijian.schedule.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.zhijian.schedule.core.ui.island.islandJellyStretch
import com.zhijian.schedule.core.ui.island.islandPillRect
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * 浮岛指示器与底栏尺寸的纯几何：不碰 Compose 的测量/绘制，也不持有状态，
 * 全部是「输入 → 输出」的算式，因此可以在本机 JVM 单测里逐条钉死
 * （见 app/src/test 下同包 IslandIndicatorLayoutTest）。
 *
 * 这里的每一式都与改造前的浮岛行为一一对应：量化步长、左右内缩、高度插值、
 * 胶囊矩形（胶囊本体的果冻几何仍由 core/ui/island 的 islandPillRect 给出）。
 */

/** 收敛进度的量化分母：1/16 一档，16 档走完一次收/展 */
internal const val COLLAPSE_PROGRESS_QUANTUM = 16f

/**
 * 把逐帧变动的收敛进度量化到 1/16。
 *
 * 量化不是为了好看，是为了省重组：animated 是逐帧变动的 Animatable，直接在组合期读它，
 * 整条浮岛（含 3 个条目、每个条目的颜色/标签高度/图标弹跳动画）会跟着每帧重组一次。
 * 真正用到它的只有「左右内缩」和「高度」两处，量化后一次收敛动画最多重组 16 次，
 * 单步 ≈1.1dp（18→48dp）/ 1dp（64→48dp），肉眼读不出台阶。
 */
internal fun quantizeCollapseProgress(raw: Float): Float =
    (raw * COLLAPSE_PROGRESS_QUANTUM).roundToInt() / COLLAPSE_PROGRESS_QUANTUM

/** 浮岛左右内缩：展开态 ↔ 收敛态之间按进度线性插值 */
internal fun islandBarSideInset(progress: Float): Dp =
    IslandInsetXExpanded + (IslandInsetXCollapsed - IslandInsetXExpanded) * progress

/** 浮岛高度：展开态 ↔ 收敛态之间按进度线性插值 */
internal fun islandBarHeight(progress: Float): Dp =
    IslandHeightExpanded + (IslandHeightCollapsed - IslandHeightExpanded) * progress

/**
 * 选中胶囊在浮岛坐标系里的矩形（px）。
 *
 * @param metrics 舱体与内边距（尺寸、格宽、内缩，全部逐帧不变，见 [IslandBarMetrics]）
 * @param slide 选中胶囊当前的 tab 下标（可为小数、可越界）
 * @param from 本次行程的起点下标
 * @param to 本次行程的终点下标
 *
 * ## 进度与方向都从 slide 反算
 * `progress = (slide - from) / (to - from)`：位移走到哪，形变就演到哪，不需要第二个动画通道。
 * `direction = sign(to - from)`：一次行程里方向恒定，且停稳后仍然有效（速度那时已经归零）。
 *
 * 起点等于终点（没在移动、或首帧）时进度取 1 = 已归位，方向取 +1——两种取值都不产生形变。
 */
internal fun islandIndicatorRect(
    metrics: IslandBarMetrics,
    slide: Float,
    from: Float,
    to: Float,
): Rect {
    val span = to - from
    val progress = if (abs(span) < 1e-4f) 1f else ((slide - from) / span).coerceIn(0f, 1f)
    val direction = if (abs(span) < 1e-4f) 0f else sign(span)
    return islandPillRect(
        box = metrics.pillBox(),
        centerX = (slide + 0.5f) * metrics.itemWidthPx,
        jelly = islandJellyStretch(progress),
        direction = direction,
    )
}
