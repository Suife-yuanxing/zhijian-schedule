package com.zhijian.schedule.navigation

import com.zhijian.schedule.core.ui.island.IslandPillBox

/**
 * 浮岛的一帧量测：舱体尺寸、格宽、胶囊与边壁的单侧间距（px）。
 *
 * 这些值在**同一次收敛进度**内全部不变：收敛动画的进度被量化到 1/16
 * （见 `IslandIndicatorLayout.quantizeCollapseProgress`），所以一整段收/展里只有 16 组量测。
 * 聚成一组的好处是 `islandIndicatorRect` 只暴露「逐帧变动」的三个入参：位置、速度、回弹进度，
 * 调用点不会出现一长串 `Float` 谁是谁的疑问。
 */
internal data class IslandBarMetrics(
    /** 浮岛本体实测宽度（px） */
    val barWidthPx: Float,
    /** 浮岛本体实测高度（px）；收敛态会矮一档，胶囊跟着矮 */
    val barHeightPx: Float,
    /** 一格 tab 的宽度（= 浮岛宽 / tab 数） */
    val itemWidthPx: Float,
    /** 胶囊与浮岛左右边壁的单侧间距（px） */
    val insetXPx: Float,
    /** 胶囊与浮岛上下边壁的单侧间距（px） */
    val insetYPx: Float,
) {
    /** 折成胶囊的舱体 + 静止尺寸，交给果冻几何（见 `islandPillRect`） */
    internal fun pillBox() =
        IslandPillBox(
            barWidth = barWidthPx,
            barHeight = barHeightPx,
            baseWidth = itemWidthPx - insetXPx * 2f,
            baseHeight = barHeightPx - insetYPx * 2f,
        )
}
