package com.zhijian.schedule.feature.schedule.components

import androidx.compose.ui.geometry.Rect

/**
 * 空课块被点中时的「生长原点」几何（纯逻辑，无 Android 依赖，可进本机单测）。
 *
 * 为什么需要它：点空课块进编辑页时，圆形扩散要从**被点的那一格**长出来，而不是从加号。
 * 但 `WeekGridBands` 里每个天列只是一整块 Box，点击回调拿到的是列内相对偏移，
 * 扩散需要的是 Compose 根坐标系里的矩形。把「列框 + 段内下标 + 节高」折成格子矩形
 * 这段算术抽出来，才能被单测钉住——它在真机上错了只表现为「圆从隔壁那格长出来」，
 * 逐帧看很难判断到底是几何错了还是动画错了。
 */
internal object EmptyCellGeometry {
    /**
     * 第 [localIndex] 行（0 起，段内）对应的格子矩形，坐标继承 [column] 所在的坐标系。
     *
     * - 水平方向整列满宽：课块本身就是整列贴边的，圆从列宽正中长出来正好是它的中心；
     * - 垂直方向按 [sectionHeightPx] 切段，并夹在列框内——向下越界的最后一行取到底边，
     *   否则浮点误差会让矩形底边溢出到下一段，圆的起手半径跟着变。
     */
    fun cellRect(
        column: Rect,
        sectionHeightPx: Float,
        localIndex: Int,
        sectionCount: Int,
    ): Rect {
        if (sectionHeightPx <= 0f || sectionCount <= 0) return column
        val index = localIndex.coerceIn(0, sectionCount - 1)
        val top = column.top + sectionHeightPx * index
        val bottom = (top + sectionHeightPx).coerceAtMost(column.bottom)
        return Rect(
            left = column.left,
            top = top.coerceAtLeast(column.top),
            right = column.right,
            bottom = bottom,
        )
    }

    /**
     * 点不落在任何格子里（理论上不会发生，防御浮点误差与布局抖动）时，退化成整列。
     *
     * 退化比返回 Rect.Zero 好：Zero 会让扩散从一个 0×0 的圆起手，起手第一帧就是整屏瞬切。
     */
    fun cellOrColumn(
        column: Rect,
        sectionHeightPx: Float,
        localIndex: Int,
        sectionCount: Int,
    ): Rect {
        if (column.width <= 0f || column.height <= 0f) return Rect.Zero
        return cellRect(column, sectionHeightPx, localIndex, sectionCount)
    }
}
