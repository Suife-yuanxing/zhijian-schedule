package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Easing
import com.zhijian.schedule.core.util.smoothstep
import kotlin.math.hypot
import kotlin.math.max

/** 一次扩散的时长与缓动 */
internal data class RevealTiming(
    val durationMillis: Int,
    val easing: Easing,
)

/**
 * 扩散时间线的分发：一次扩散的方向（reverse）与语义（换主题 / 换页面）决定用哪套时长与缓动。
 *
 * - 页面正向（加号 → 编辑页）与换主题：同一条加速曲线 [Motion.EaseRevealAccelerate]，
 *   时长各自补偿过（见 Motion 里两个常量的推导）；
 * - 页面反向（返回收回）：[Motion.EaseRevealDecelerate]，正向曲线的严格时间镜像——
 *   方向相反但不打架，反向就是正向的倒放；
 * - [reverse] 优先于 [themeSwitch]：反向只可能来自 requestNavigationBack（toId 恒为 null），
 *   两者同时为真不可达；这条优先级只是把「不可达组合」的语义提前定死，免得日后改动踩空。
 */
internal fun revealTiming(
    reverse: Boolean,
    themeSwitch: Boolean,
): RevealTiming =
    when {
        reverse -> RevealTiming(Motion.DUR_REVEAL_BACK, Motion.EaseRevealDecelerate)
        themeSwitch -> RevealTiming(Motion.DUR_REVEAL_THEME, Motion.EaseRevealAccelerate)
        else -> RevealTiming(Motion.DUR_REVEAL, Motion.EaseRevealAccelerate)
    }

/**
 * 反向遮罩的淡出系数：进度 0.9 之前整块不透明，到 1 完全化掉，
 * 让圆收到加号大小时由真实的加号接手，不留一个硬边的圆点。
 * 圆外不参与——那里是实时内容（见 [RevealHost] 反向分支）。
 */
internal fun reverseMaskAlpha(progress: Float): Float = 1f - smoothstep(0.9f, 1f, progress)

/**
 * 圆心到屏幕最远角的距离：扩散半径的终点。
 * 按「水平最远边 + 垂直最远边」合成，圆心贴边或落在屏幕外时同样成立，
 * 保证圆最终一定完整覆盖整块画布。
 */
internal fun revealRadius(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
): Float {
    val dx = max(centerX, width - centerX)
    val dy = max(centerY, height - centerY)
    return hypot(dx, dy)
}
