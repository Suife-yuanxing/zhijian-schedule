package com.zhijian.schedule.core.ui.island

import androidx.compose.ui.geometry.Rect
import kotlin.math.sin

/*
 * 底部浮岛选中胶囊的「果冻」几何：**移动途中先拉长、然后回收**。
 *
 * ## 一条时间线，两半形状
 * 整段行程由「位移进度 u（0 = 起步，1 = 停稳）」驱动。形变波形在同一个 u 上给出：
 * 前半程拉长（正）、中点归原形、后半程回收（负）、抵达那一帧回到原形。
 * 所以用户看到的是「嗖——拉长飞过去，到跟前收拢、顿一下」，而不是「滑到位置后原地蹦两下」。
 *
 * ## 为什么不再用「速度驱动 + 到站回弹」两段
 * 速度驱动的旧做法有个结构性缺陷：速度在落位前就归零，形变必然跟着归零，
 * 于是「停下来之后再晃」只能另起一段时间——那就成了两件事，真机上读作「滑过去 → 站定 → 蹦」。
 * 改成按进度走之后，形变覆盖整段行程，抵达即归位，结构上只可能是一件事。
 *
 * ## 不越舱、不糊边
 * 拉伸上限 +16%（滑行满拉伸），回收下限 −8.8%；描边随形状重画（圆角恒等于高的一半），
 * 胶囊两端始终是正半圆，不会因为形变糊成一片。
 */

/**
 * 果冻波形：`LEAD·sin(π·x)²` 的正峰 − `TRAIL·sin(π·x)²` 的负谷，两端为 0；返回值是**带符号的拉伸单位**。
 *
 * 符号约定：正值 = 比静止更宽（出行拉长），负值 = 比静止更窄（到站回收）。
 *
 * ## 一个行程里只拉一次、只收一次
 * 前半程 [LEAD_PEAK] 处吃满拉伸（宽度 +16%），中点回到原形，后半程 [TRAIL_TROUGH] 处收到最窄
 * （−8.8%），再贴回原形。两个峰都在**位移区间之内**，抵达那一刻形状已经归位。
 *
 * ## 为什么用两条 `sin(π·x)²` 而不是一条 `sin(2π·u)`
 * 单条正弦的正峰在 u=0.25、负谷在 u=0.75，形状对，但两峰**高度被锁死相等**（1 与 −1）。
 * 果冻需要「拉得开、收得轻」——拉长是主戏，回收只是把拉出去的量收回来的余势。
 * 拆成两条各占半程的波形之后，幅度可以分别给。
 *
 * ## 为什么用平方而不是一次幂
 * 一次幂在分段边界（u=0.5）斜率不为零，两段拼起来会在中点留一个折角，读作「拉伸突然换挡」。
 * 平方后每段两端导数都是 0，接缝天然平滑（逐点复核差值 ±0.002）。
 *
 * ## 为什么两端必须为 0
 * u=0 是起步（静止态）、u=1 是停稳（静止态）。两端不为 0 就等于形变凭空跳出来、或停住还留着，
 * 两种都会读成硬边。
 */
internal fun islandJellyWave(progress: Float): Float {
    val u = progress.coerceIn(0f, 1f)
    return LEAD_PEAK * lobe(u / JELLY_SPLIT) - TRAIL_TROUGH * lobe((u - JELLY_SPLIT) / (1f - JELLY_SPLIT))
}

/**
 * 单边瓣：`sin(π·x)²`，x 在 0..1 之外被夹住（所以两条瓣各自只在自己那半程里起作用，另一瓣为 0）。
 *
 * 用平方而不是取绝对值：绝对值在 x=0 与 x=1 处有折角，拼到行程两端就是「起步一抖」。
 */
private fun lobe(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    val s = sin(PI * v)
    return s * s
}

/** π（Float）：避免每帧把 Double 常量搬进浮点运算 */
private const val PI = 3.1415927f

/** 行程的中点：前半程拉长、后半程回收的分界（进度 0.5） */
private const val JELLY_SPLIT = 0.5f

/**
 * 出行相的峰值**拉伸单位**（1.0 = 宽度 +16%，与 [IslandTokens.PILL_GROW] 同一个刻度）。
 *
 * 取 1.0：88dp 的胶囊在进度 25% 时宽到 102.1dp（+16%）、高到 42.7dp（−11%）。
 * 这是「被拽着走」的极限幅度——再大就会顶到相邻一格（格宽 108dp，两侧还要各留 10dp 内边距）。
 */
private const val LEAD_PEAK = 1f

/**
 * 回收相的谷值**拉伸单位**（负值 = 比静止更窄）。
 *
 * 取 0.55：88dp 收到 80.3dp（−8.8%）、高到 50.9dp（+6%）。
 * 比出行峰值小一半，是因为回收只是「把拉出去的量收回来」的余势；
 * 幅度与拉长相同会读成「反向又飞了一次」。
 */
private const val TRAIL_TROUGH = 0.55f

/**
 * 把波形折算成 [islandPillRect] 能用的拉伸单位。
 *
 * 保留符号一路传下去：负值交给 [islandPillRect] 换算成「比静止更窄、比静止更高」，
 * 那是体积守恒的回收相，也是旧实现结构上做不到的那一下（拉伸量以前被夹在 0..1）。
 */
internal fun islandJellyStretch(progress: Float): Float = islandJellyWave(progress)

/**
 * 舱体与胶囊静止尺寸（逐帧不变的那一组）。
 *
 * 与「这一刻的形态」无关，所以聚成一组：[islandPillRect] 的签名就只剩三件逐帧变动的事
 * （位置、带符号的拉伸、方向），调用点不会出现「一长串 Float 谁是谁」的问题。
 */
internal data class IslandPillBox(
    /** 浮岛本体尺寸（px） */
    val barWidth: Float,
    val barHeight: Float,
    /** 胶囊静止尺寸（px）= 一格宽减去左右内边距 / 浮岛高减去上下内边距 */
    val baseWidth: Float,
    val baseHeight: Float,
)

/**
 * 果冻胶囊在浮岛坐标系里的矩形（px）。
 *
 * @param box 舱体与胶囊静止尺寸（逐帧不变的那一组，见 [IslandPillBox]）
 * @param centerX 胶囊中心；由 tab 下标插值得到，过冲时会略微越过两端
 * @param jelly **带符号**的拉伸单位（见 [islandJellyWave]）：正 = 出行拉长，负 = 到站回收
 * @param direction 行进方向（-1 / 0 / +1），决定拉长量分给前缘还是后缘
 *
 * ## 一条带符号的量就够了
 * 旧实现用 `stretch`（0..1，只正向）加 `settle`（另起一段时间）两个数，结构上就决定了
 * 「先滑完、再原地回弹」——那是两件事。现在整段行程由**一个带符号的数**描述，
 * 拉长与回收是同一条曲线的前后半程，几何上不可能分家。
 *
 * ## 为什么是「前缘多、后缘少」
 * 对称拉伸读作「原地涨大」，非对称才读作「被拽着走的水滴」：鼻尖先出去，尾巴拖在后面。
 * 分配比例 [lead] ≈ 0.62，形心因此前移约 1.7dp——小到不破坏对位的信任，又足以让方向可读。
 * 回收相传进来的是负值，同一条分配式自然变成「前缘先收回来」，不需要另写一套。
 *
 * ## 高度
 * 宽了就要矮（体积守恒）。压缩上限 [squash] = 0.11：再多会读成扁条，再少看不出被拉长。
 * 高度这一维吃的也是同一个带符号的 `jelly`：正时变矮、负时变高，
 * 因为「被收窄」就是把横着的体积挤到竖着去——一个系数、一物理。
 *
 * ## 夹紧只在舱壁
 * 顶到浮岛边界时被压回来，不会从舱里流出去；常规行程里用不到这条分支，
 * 它是快速连点 / 极端过冲时的保险。
 */
internal fun islandPillRect(
    box: IslandPillBox,
    centerX: Float,
    jelly: Float,
    direction: Float,
    grow: Float = IslandTokens.PILL_GROW,
    squash: Float = IslandTokens.PILL_SQUASH,
    lead: Float = IslandTokens.PILL_LEAD,
): Rect {
    val barWidth = box.barWidth
    val barHeight = box.barHeight
    val baseWidth = box.baseWidth
    val baseHeight = box.baseHeight
    // 带符号：正 = 拉长（出行），负 = 收窄（到站回收）。同一个自由度、同一把尺子（PILL_GROW）。
    val extra = baseWidth * grow * jelly
    // 高度跟着带符号的总拉伸反向走：拉长就变矮、收窄就变高（体积守恒）。
    val height = (baseHeight * (1f - squash * jelly)).coerceIn(1f, barHeight)
    // 出行相「鼻尖先出去、尾巴拖后」；回收相负值把这条非对称翻转，读作「前缘先收回来」。
    // 两者是同一条方向敏感的分配式：负的 extra 自动让前缘缩得更多，不需要另写一套。
    val leadExt = extra * lead
    val trailExt = extra * (1f - lead)
    // 往右走时右缘是鼻尖；往左时镜像
    val rightExt = if (direction < 0f) trailExt else leadExt
    val leftExt = if (direction < 0f) leadExt else trailExt
    val half = baseWidth / 2f
    // 夹紧只作用于浮岛边界：胶囊顶到舱壁时被压回来，不会从浮岛里流出去
    val left = (centerX - half - leftExt).coerceIn(0f, (barWidth - 1f).coerceAtLeast(0f))
    val right = (centerX + half + rightExt).coerceIn((left + 1f).coerceAtMost(barWidth), barWidth)
    val top = (barHeight - height) / 2f
    return Rect(left = left, top = top, right = right, bottom = top + height)
}
