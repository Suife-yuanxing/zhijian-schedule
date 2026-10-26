package com.zhijian.schedule.core.ui.island

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * 底部浮岛选中胶囊的「果冻」几何。
 *
 * ## 与果冻开关同源
 * 形变不是另起一条 0→1 的形变动画，而是把胶囊的**实时速度**折算成拉伸量。
 * 速度与位移天生同拍：速度归零就必然收成胶囊原形，不存在「形变还没演完、位移已经到站」
 * 的错拍，也不会给一次切换多加一段时间线。
 *
 * ## 为什么这次可以再做形变
 * 旧版形变被删掉（见 [com.zhijian.schedule.core.ui.Motion.FluidSlide] 的推导），当年三条
 * 批评各有一条现在站不住了：
 * 1) 「无意义地鼓动」——旧版是**独立关键帧**，动得再欢也与位移无关；速度驱动的形变只复述
 *    位移自身的快慢与方向，是同一个信号的另一种画法，不是第二件事。
 * 2) 「把切换拖到 339ms」——这里没有新增时间线，拉伸在 ~150ms 内随速度自然收完，
 *    总时长仍是 [com.zhijian.schedule.core.ui.Motion.FluidSlide] 的 217ms。
 * 3) 「边界被拉糊」——只做 16% 拉伸，且描边**随形状重画**（圆角恒等于高的一半），
 *    胶囊两端始终是正半圆，不会糊成一片。
 */

/**
 * 单步（跨 1 个 tab）的峰值速度。
 *
 * 推导：spring(ζ 0.9, k 600) 解单位阶跃，ω = √600 ≈ 24.50，ω_d = ω√(1-ζ²) ≈ 10.68，
 * 峰值出现在 t* = atan(ω_d/ζω)/ω_d ≈ 50ms，代入得 v_peak ≈ 9.51 /s（逐帧积分复核 9.509）。
 * 这一版弹簧刻意**没有过冲**（0.10%），所以形变是单峰脉冲、不是来回抖两下。
 */
internal const val ISLAND_PEAK_SPEED = 9.51f

/**
 * 把速度折算成 0..1 的拉伸量。
 *
 * @param speed 胶囊滑行的实时速度，单位是「每秒走几个 tab」
 * @param travel 本次动画跨过的 tab 数；归一化基准按它放大，所以跨 2 格不会一路顶在满拉伸
 *
 * 取绝对值：往左往右都是被拉长，形变不该分方向（方向只决定前缘还是后缘多吃）。
 */
internal fun islandStretch(speed: Float, travel: Float = 1f): Float {
    val reference = ISLAND_PEAK_SPEED * travel.coerceAtLeast(1f)
    return (abs(speed) / reference).coerceIn(0f, 1f)
}

/**
 * 果冻胶囊在浮岛坐标系里的矩形（px）。
 *
 * @param baseWidth / baseHeight 静止尺寸（= 一格宽减去左右内边距 / 浮岛高减去上下内边距）
 * @param centerX 胶囊中心；由 tab 下标插值得到，过冲时会略微越过两端
 * @param stretch 0 = 原形，1 = 拉满
 * @param direction 速度方向（-1 / 0 / +1），决定拉伸量分给前缘还是后缘
 *
 * ## 为什么是「前缘多、后缘少」
 * 对称拉伸读作「原地涨大」，非对称才读作「被拽着走的水滴」：鼻尖先出去，尾巴拖在后面。
 * 分配比例 [lead] ≈ 0.62，形心因此前移约 1.7dp——小到不破坏对位的信任，又足以让方向可读。
 *
 * ## 高度
 * 宽了就要矮（体积守恒的错觉）。压缩上限 [squash] = 0.11：再多会让胶囊读成一根扁条，
 * 再少就看不出「被拉长」。
 */
internal fun islandPillRect(
    barWidth: Float,
    barHeight: Float,
    baseWidth: Float,
    baseHeight: Float,
    centerX: Float,
    stretch: Float,
    direction: Float,
    grow: Float = IslandTokens.PILL_GROW,
    squash: Float = IslandTokens.PILL_SQUASH,
    lead: Float = IslandTokens.PILL_LEAD,
): Rect {
    val s = stretch.coerceIn(0f, 1f)
    val extra = baseWidth * grow * s
    val height = (baseHeight * (1f - squash * s)).coerceIn(1f, barHeight)
    val leadExt = extra * lead
    val trailExt = extra - leadExt
    // 往右走时右缘是鼻尖；往左走时镜像
    val rightExt = if (direction < 0f) trailExt else leadExt
    val leftExt = if (direction < 0f) leadExt else trailExt
    val half = baseWidth / 2f
    // 夹紧只作用于浮岛边界：胶囊顶到舱壁时被压回来，不会从浮岛里流出去
    val left = (centerX - half - leftExt).coerceIn(0f, (barWidth - 1f).coerceAtLeast(0f))
    val right = (centerX + half + rightExt).coerceIn((left + 1f).coerceAtMost(barWidth), barWidth)
    val top = (barHeight - height) / 2f
    return Rect(left = left, top = top, right = right, bottom = top + height)
}
