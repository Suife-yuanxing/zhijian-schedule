package com.zhijian.schedule.core.ui

/**
 * 分段关键帧求值：把单一动画进度 [progress]（0..1）映射到多通道（缩放/位移/旋转），
 * 让一套弹跳的所有通道共享同一节拍；points 按时间升序，超出两端取端点值。
 */
internal fun keyframed(progress: Float, vararg points: Pair<Float, Float>): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= points.first().first) return points.first().second
    for (i in 1 until points.size) {
        val (t, v) = points[i]
        if (p <= t) {
            val (pt, pv) = points[i - 1]
            return pv + (v - pv) * ((p - pt) / (t - pt))
        }
    }
    return points.last().second
}
