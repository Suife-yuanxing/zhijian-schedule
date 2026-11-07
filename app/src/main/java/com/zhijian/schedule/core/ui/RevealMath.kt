package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.util.lerpFloat
import com.zhijian.schedule.core.util.smoothstep
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
 * 被点控件「长什么样」——只在页面扩散（进编辑页）时才需要。
 *
 * 为什么扩散要知道颜色与字形：加号方块在扩散期间**必须一直可见**（需求要求它撑到页面
 * 铺满之后才离场），而它身下的那块像素此刻已经被新页面接管了。于是浮层要按这份描述
 * 重画一枚一模一样的方块盖在那里，接缝才不会露。空课块 / 空状态卡片没有副本（它们下面
 * 没有需要遮盖的东西），这个字段留空即可。
 */
data class SeedBadge(
    val fill: Color,
    val tint: Color,
    /** 非空 = 金属键帽的球冠高光（按 1/3 半径、左上偏移画一团柔光） */
    val highlight: Color? = null,
)

/**
 * 扩散的「种子」：被点控件**实测**的轮廓（圆角矩形）+ 可选的视觉副本。
 *
 * 为什么不是圆心 + 半径：需求要把「页面沿着整个加号方块展开」做到可测量——
 * 方块是圆角矩形，它四条边到屏幕四边的距离各不相同，用单一半径去描述必然有一侧先到、
 * 另一侧还差一截，观感上就是「圆不是从方块里长出来的」。改成轮廓之后，「边界始终是
 * 方块自己的等距外扩」是构造出来的性质，不是调出来的参数。
 */
data class SeedShape(
    /** 控件在**根坐标系**里的矩形 */
    val bounds: Rect,
    /** 实测圆角半径（px）：方块取自身 shape 的角半径，空课块取课块圆角 */
    val cornerRadius: Float,
    /** 非空 = 扩散期间要重画这枚控件（加号方块），页面铺满后把它缩小离场 */
    val badge: SeedBadge? = null,
)

/**
 * 把 [seed] 等距外扩到覆盖整块画布所需的距离。
 *
 * 外扩 g 之后轮廓是「矩形四边各向外 g、圆角半径 +g」——这是圆角矩形的 Minkowski 和，
 * 边界上任一点到原轮廓的最短距离恒等于 g。因此覆盖整屏的充要条件是四条边都越界：
 * left - g <= 0、right + g >= width、上下同理，取四者最大值即最小可行 g。
 *
 * 不用「对角线长度」：那是圆的口径。方块贴右下角时对角线会把上边与左边各推开一大截，
 * 起手那几十毫秒里方块外会先露出一圈多余的新页面——正好是要修的病灶。
 */
internal fun dilationToCover(
    seed: SeedShape,
    width: Float,
    height: Float,
): Float {
    val b = seed.bounds
    val horizontal = max(b.left, width - b.right)
    val vertical = max(b.top, height - b.bottom)
    // +2 是抗锯齿留边：外扩距离算到「刚好贴边」时，最后一帧仍可能看到一条 1px 的旧页线
    return max(0f, max(horizontal, vertical)) + 2f
}

/**
 * 当前外扩距离：正向 0 → [end]，反向 [end] → 0。
 *
 * 正向从 **0** 起手（不是从「刚好盖住控件」起手）：外扩 0 时轮廓**就是**方块本身，
 * 圆角、边长、位置全都对得上，方块与它下面那片新页面严丝合缝——
 * 这才是「页面从方块里长出来」，而不是「一个比方块大的圆把方块吃掉」。
 */
internal fun dilationAt(
    start: Float,
    end: Float,
    progress: Float,
): Float {
    val t = progress.coerceIn(0f, 1f)
    return start + (end - start) * t
}

/**
 * 外扩 [dilation] 之后的圆角矩形。
 *
 * 圆角半径随外扩同步增长，否则形状会从「圆角方块」变成「方角方块」再变成圆——
 * 中途那些帧里轮廓与种子不是同一族，眼睛读得到「形状变了」。上限取短边一半
 * （超过就退化成胶囊，RoundRect 不接受非法半径）。
 */
internal fun dilatedRound(
    seed: SeedShape,
    dilation: Float,
): RoundRect {
    val b = seed.bounds
    val left = b.left - dilation
    val top = b.top - dilation
    val right = b.right + dilation
    val bottom = b.bottom + dilation
    val limit = max(0f, minOf(right - left, bottom - top) / 2f)
    val radius = (seed.cornerRadius + dilation).coerceIn(0f, limit)
    return RoundRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        cornerRadius = CornerRadius(radius, radius),
    )
}

/**
 * 反向遮罩的淡出系数：进度 0.9 之前整块不透明，到 1 完全化掉，
 * 让轮廓收回方块大小时由真实的加号接手，不留一圈硬边的旧页。
 * 轮廓外不参与——那里是实时内容（见 RevealHost 反向分支）。
 */
internal fun reverseMaskAlpha(progress: Float): Float = 1f - smoothstep(0.9f, 1f, progress)

/**
 * 方块退场的形状：**内缩 + 圆形化**，与页面扩张同族。
 *
 * ## 为什么退场不能再缩放
 *
 * 扩张用的算子是**闵可夫斯基外扩**：dilatedRound 把矩形四条边各推开 d、圆角同时 +d。
 * 它是平移型的——边界上每一点沿各自法线等速外移，形状比 r/side 随 d 单调趋近 1/2
 * （越胀越圆）。而 scale 是相似变换：每一点按到中心的距离按比例内移，形状比恒定不变。
 * 两者只在 d = 0（也就是 p = 0）这一帧重合，之后分属两族曲线——一个越缩越方，一个越胀越圆。
 * 这正是旧版读作「白色页面上有个橙色小方块」的根因：**没有任何一条缓动曲线能把相似变换
 * 调到与外扩同族**，它俩的几何关系不是时间问题。
 *
 * ## 也不能用「外扩的逆运算」
 *
 * 严格的逆是**腐蚀**（四边等速内移）。但扩张要走 1898px 才盖满，腐蚀只有 72px 可用——
 * 按同一速率，方块在 p = 72/1898 ≈ 0.038（约 15ms）就被吃干净，等于瞬移消失。
 * 所以退场不能是扩张的逆，只能是**在同一个形状族里朝同一个吸引子走**。
 *
 * ## 于是：两头都朝「圆」去
 *
 * 扩张让形状比从 1/3 走到 1/2（圆），退场就让方块也从 1/3 走到 1/2，只是同时缩小。
 * 任一帧上，边界与方块是同一族曲线上朝相反方向走的两点，这就是「同一套语言」的可见形式。
 */
internal fun seedRoundness(progress: Float): Float = smoothstep(0f, SEED_ROUND_END, progress)

/** 圆形化在整段进度的哪个点走完：0.32 ≈ 215ms，此后方块已经是正圆，只剩内缩与化掉 */
private const val SEED_ROUND_END = 0.32f

/**
 * 方块的**内缩**进度 0..1：四边等速往里退，这就是腐蚀的算子（只是幅度小得多）。
 *
 * 幅度取「短边的 1/4」：方块 144px 时内缩 36px，边长收到 72px（一半）。
 * 再大就会在还看得见的那些帧里缩成一颗过小的点，读作「远处有个光标」；
 * 再小则整段退场只剩淡出，丢掉「被吃掉」的体感。
 */
internal fun seedInsetProgress(progress: Float): Float = smoothstep(SEED_INSET_START, SEED_INSET_END, progress)

/** 内缩的起步点：0.06 ≈ 66ms，让用户先看到方块在完整尺寸上被页面越过一次 */
private const val SEED_INSET_START = 0.06f

/** 内缩的收口点：与淡出同一个终点，圆角 / 内缩 / 透明度三者在 0.44 一起归位 */
private const val SEED_INSET_END = 0.44f

/** 内缩量占短边的比例：0.25 ⇒ 边长收到一半 */
private const val SEED_INSET_FRACTION = 0.25f

/**
 * 方块退场时的轮廓（宿主坐标）。[roundOut] 为 false（素墨）时不做圆形化、也不内缩，
 * 只靠透明度离场——「不转不缩」那条主题约束不该被这里的几何改动牵动。
 *
 * [progress] 为 0 时结果与 [dilatedRound] 的 0 外扩**逐字段相等**：
 * 退场的第一帧必须还是那个方块本身，否则一按下去它会先跳一下。
 */
internal fun shrunkenRound(
    seed: SeedShape,
    progress: Float,
    roundOut: Boolean,
): RoundRect {
    val b = seed.bounds
    val t = progress.coerceIn(0f, 1f)
    val inset =
        if (roundOut) {
            minOf(b.width, b.height) * SEED_INSET_FRACTION * seedInsetProgress(t)
        } else {
            0f
        }
    val left = b.left + inset
    val top = b.top + inset
    val right = b.right - inset
    val bottom = b.bottom - inset
    val limit = max(0f, minOf(right - left, bottom - top) / 2f)
    // 圆角朝「半边」去就是朝正圆去；roundOut=false 时保持种子原角，只受合法性上限约束
    val target = if (roundOut) limit else seed.cornerRadius
    val radius = lerpFloat(seed.cornerRadius, target, seedRoundness(t)).coerceIn(0f, limit)
    return RoundRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        cornerRadius = CornerRadius(radius, radius),
    )
}

/**
 * 方块本体离场的不透明度。
 *
 * 窗口是 0.18→0.62（约 167ms→304ms）。两点约束卡出来的：
 * - 起点不能早于 0.18：加号转叉号要 170ms，转的过程必须在满不透明下被看见；
 * - 终点必须早于页面铺满：方块的生命周期落在「页面还在明显扩张」的那一段，
 *   不能拖到边界已经贴边之后还剩一颗小点在慢慢消失——那正是要修的观感。
 * 0.62 时外扩已到 1177px、约 8 个方块宽，屏幕上早已读作「页面」，方块只是它里面的一点余温。
 */
internal fun seedAlpha(progress: Float): Float = 1f - smoothstep(SEED_ALPHA_START, SEED_ALPHA_END, progress)

private const val SEED_ALPHA_START = 0.18f
private const val SEED_ALPHA_END = 0.62f

/**
 * 字形的存活度：比方块本体**更早**走完。
 *
 * 这是 container transform `fadeMode = THROUGH` 的先后语义——内容先离场、容器后离场。
 * 顺序反过来
 * （方块先化、叉号还挂着）会读作「一个叉浮在页面上」，比「一个方块浮在页面上」更糟。
 * 起点压在 0.24（约 186ms）之后：加号转叉号的 170ms 要在**满不透明**下走完，
 * 否则那个叉号是半透明地出现又消失，等于白转。
 */
internal fun seedGlyphAlpha(progress: Float): Float = 1f - smoothstep(SEED_GLYPH_START, SEED_GLYPH_END, progress)

private const val SEED_GLYPH_START = 0.24f
private const val SEED_GLYPH_END = 0.40f

/**
 * 边界描边的不透明度：**方块的颜色移交给页面的边界**。
 *
 * 这是「同一套语言」里除了形状之外的第二条线索。形状只说明「它和页面同族」，
 * 颜色才说明「就是它变的」。整段退场没有别的颜色传递：方块本体一化掉，橙色就彻底消失，
 * 页面从浅底开始就是它自己的底色——那就等于两个无关的东西前后出现。
 *
 * 描边有两种画法，这里必须选对：**居中对齐的 Stroke 会有一半画在轮廓外面**。
 * 若第 0 帧就满不透明，方块的四周会先多出一圈 1dp 的橙色——虽然同色、读作「方块大了一点」，
 * 但那是接缝。所以这条不透明度不是「从 1 开始衰减」，而是**先涨后落**：
 * 0 → 0.10 涨满（此时边界已离开方块 152px，橙色整条都在方块之外），0.10 → 0.28 保持，
 * 0.28 → 0.55 化掉。
 *
 * 读作「方块的颜色被页面拉成了它的边」，而不是「又多了个橙色边框」。
 */
internal fun seedEdgeAlpha(progress: Float): Float =
    smoothstep(0f, SEED_EDGE_IN, progress) * (1f - smoothstep(SEED_EDGE_START, SEED_EDGE_END, progress))

/** 描边涨满的进度点：0.10 ≈ 116ms，此时外扩已到 190px，整条描边都在方块之外 */
private const val SEED_EDGE_IN = 0.10f

private const val SEED_EDGE_START = 0.28f
private const val SEED_EDGE_END = 0.55f

/** 球冠高光：半径与圆心（[center] 相对于方块左上角） */
internal class SeedHighlight(
    val radius: Float,
    val center: Offset,
)

/** 高光半径占短边的比例：必须小于 0.5（键帽半径），否则光斑会溢出圆外 */
private const val HIGHLIGHT_RADIUS_RATIO = 0.34f

/** 高光圆心在方块内的水平偏移比例 */
private const val HIGHLIGHT_CENTER_X = 0.32f

/** 高光圆心在方块内的垂直偏移比例 */
private const val HIGHLIGHT_CENTER_Y = 0.26f

/**
 * 金属键帽的球冠高光几何：半径取短边的三分之一、圆心偏向左上。
 *
 * 这几个比例不是随手写的：键帽是 RoundedCornerShape(50%) 的正圆，球冠高光必须落在
 * 圆内的左上象限、且半径小于圆的半径，才能读作「光从左上打过来」而不是「叠了一个圆点」。
 * 把比例抽成纯函数是为了让「方块副本」与金属主题原来的键帽画法用的是同一套数。
 */
internal fun seedHighlight(
    width: Float,
    height: Float,
): SeedHighlight {
    val side = minOf(width, height)
    return SeedHighlight(
        radius = side * HIGHLIGHT_RADIUS_RATIO,
        center = Offset(width * HIGHLIGHT_CENTER_X, height * HIGHLIGHT_CENTER_Y),
    )
}

/**
 * 圆心到屏幕最远角的距离：**换主题**扩散的终点半径。
 *
 * 主题胶囊仍走圆形口径（那是换皮肤的既有语义，与本次的页面扩散无关）：
 * 圆的边界只有半径一个自由度，必须一直长到最远角才盖满。
 * 页面扩散（加号 / 空课块）改用 [dilationToCover] 的轮廓口径。
 */
internal fun revealRadius(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
): Float {
    val dx = max(centerX, width - centerX)
    val dy = max(centerY, height - centerY)
    return kotlin.math.hypot(dx, dy)
}
