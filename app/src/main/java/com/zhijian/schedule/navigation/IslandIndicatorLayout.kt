package com.zhijian.schedule.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.zhijian.schedule.core.ui.island.islandPillRect
import com.zhijian.schedule.core.ui.island.islandStretch
import kotlin.math.roundToInt
import kotlin.math.sign

/**
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
 * 位置以 **tab 下标** slide 为单位：中心 = (slide + 0.5) × 一格宽，因此速度单位是
 * 「每秒走几个 tab」，与屏宽、tab 数无关；拉伸量由该速度折算（见 islandStretch）。
 * 内边距从浮岛尺寸里扣除后才是胶囊的静止尺寸，所以文字/图标的居中对位不受影响。
 *
 * @param barWidthPx / barHeightPx 浮岛本体的实测尺寸（px）
 * @param itemWidthPx 一格 tab 的宽度（= 浮岛宽 / tab 数）
 * @param slide 选中胶囊当前的 tab 下标（可为小数、可越界）
 * @param velocity 胶囊滑行的实时速度（每秒几个 tab），决定拉伸与方向
 * @param travel 本次动画跨过的 tab 数，拉伸归一化的基准
 * @param insetXPx / insetYPx 胶囊与浮岛边缘的单侧间距（px）
 */
internal fun islandIndicatorRect(
    barWidthPx: Float,
    barHeightPx: Float,
    itemWidthPx: Float,
    slide: Float,
    velocity: Float,
    travel: Float,
    insetXPx: Float,
    insetYPx: Float,
): Rect = islandPillRect(
    barWidth = barWidthPx,
    barHeight = barHeightPx,
    baseWidth = itemWidthPx - insetXPx * 2f,
    baseHeight = barHeightPx - insetYPx * 2f,
    centerX = (slide + 0.5f) * itemWidthPx,
    stretch = islandStretch(velocity, travel),
    direction = sign(velocity),
)
