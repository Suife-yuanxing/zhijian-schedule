package com.zhijian.schedule.core.util

import androidx.compose.ui.geometry.Rect

/*
 * 动效与几何共用的数学原语。
 *
 * 全部为纯函数、无 Compose 运行时依赖（只用到 [Rect] 这种值类型），
 * 因此可在 JVM 单测里直接钉死；此前 `smoothstep` 在扩散与入口卡两处逐字重复，
 * 合并到这里避免两套实现日后漂移。
 */

/** 线性插值。 */
internal fun lerpFloat(
    start: Float,
    target: Float,
    fraction: Float,
): Float = start + (target - start) * fraction

/** 矩形逐边线性插值。 */
internal fun lerpRect(
    start: Rect,
    target: Rect,
    fraction: Float,
): Rect =
    Rect(
        left = lerpFloat(start.left, target.left, fraction),
        top = lerpFloat(start.top, target.top, fraction),
        right = lerpFloat(start.right, target.right, fraction),
        bottom = lerpFloat(start.bottom, target.bottom, fraction),
    )

/**
 * Hermite 平滑阶跃：在 [edge0, edge1] 内做三次 ease-in-out，区间外钳到 0 / 1。
 * 两端一阶导为 0，所以过渡起止都「软」，不会有可见折角。
 *
 * [edge0] 与 [edge1] 相等时按阶跃处理（避免除零）：值 >= edge0 记 1，否则记 0。
 */
internal fun smoothstep(
    edge0: Float,
    edge1: Float,
    value: Float,
): Float {
    if (edge0 == edge1) return if (value < edge0) 0f else 1f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
