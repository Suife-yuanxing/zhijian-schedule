package com.zhijian.schedule.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡面文字的「深度层级」：这一行浮在卡面上多高、静止时吃多强的浮雕明暗。
 *
 * ## 为什么单靠倾动不够
 *
 * 业界对"3D 卡片"最普遍的误解，写在 motir-core 的 3D-immersive 规范里：
 * 「**3D is layered parallax, not a tilting flat plane**」——内容全贴在卡面上、
 * 跟着整块矩形一起转，观感只会是"一张会歪的平板"。真正的立体感来自
 * **各层处在不同的 Z 平面，倾动时彼此错开**（同一份规范把卡片拆成
 * 标题 ~42px / 正文 0 / 页脚 ~14px 三层）。
 *
 * 本项目已有 [cardTiltParallax] 做"按深度平移"，但学期概览卡此前是**整列文字共用
 * 12dp 同一个深度**——所有字同进同退，等于没有层级。这里把它拆成 [CardDepthLadder]
 * 五档，层与层之间的偏序是可被单测钉住的（见 `CardDepthTextTest`）。
 *
 * 为什么不直接复用 [cardTiltParallax]：它把公式写死在 graphicsLayer 的 lambda 里，
 * 纯 JVM 单测拿不到。本文件把**同一条公式**（同样的方向、同样的 px/度）抽成
 * [parallaxDrift]，并补上 [cardTiltParallax] 缺的夹取：回正弹簧 [Motion.TiltReturn]
 * 有约 27% 过冲，不夹的话过冲那一帧的位移会超过该层深度，"层间比例 = 深度比"这条
 * 不变量当场失效。两处公式必须同步改（`CardDepthTextTest` 钉住了等价关系）。
 *
 * ## 两个通道，覆盖"动"与"静"两种状态
 *
 * 1. [depth]（**动**）：倾动时该层的视差位移量，严格与它成正比（见 [parallaxDrift]）。
 *    这是 3D 感的唯一来源，也是唯一会随手指变化的量。
 * 2. [relief]（**静**）：倾动是交互态；截图、静态浏览、系统关闭动画
 *    （ANIMATOR_DURATION_SCALE == 0）时位移恒为 0，若把 3D 全押在视差上，
 *    那些场景里卡片会退回成一张纸。所以每层再配一层"光从上方来"的字形明暗面
 *    （[inkVolume]），静止也读得出谁在前。
 *
 * ## 取值依据
 *
 * - 层级不是"字号越大浮得越高"，而是**视觉重心**决定：素材站 parallax-title-card 的
 *   三层取 1.05em（eyebrow）/ 0.54em（大标题）/ 0.62em（副标），小字反而最靠前。
 *   本卡的最大视觉重心是「第 9 周」这枚数字，所以它占最高层；眉标（学期胶囊）次之，
 *   带单位的说明（共 20 周）再靠后，事实类信息（开学日期、进度条、页码）留在卡面。
 * - 正文留在 Z=0 不是偷懒：3D-immersive 规范 §3 明确写着"倾斜/抬起的平面上小字会有
 *   亚像素发虚，长文本必须留在卡面，只有短的大标题/标签可以前移"。
 * - 幅度：焦点大卡最大倾角 17°（[rememberFocusCardTiltStyle]），最高层 10dp 在满倾时
 *   漂移 10dp≈30px；层间差 10 / 7 / 5 / 3 / 0 已经能读出"错开"，再大就会撞到右侧
 *   绩点块或挤进下一行——戏剧性交给层间比例，不靠绝对值。
 *
 * ## 为什么删掉了文字投影（Shadow）
 *
 * 上一版给每层叠了一道 [androidx.compose.ui.graphics.Shadow]，真机截图否掉了它：
 * Compose 的文字投影本质是"把字形的 alpha 蒙版整体模糊一次、再错位盖下去"，
 * 所以它**永远比正文低一档锐度**。480dpi（density 3）下按 4.2×relief×density 取模糊
 * 半径，Hero 层落到 12.6px 模糊、6.3px 落差、0.34 不透明度的深蓝——而「第 9 周」
 * 这类 CJK 字形的笔画本身只有 8~10px 宽，于是那层影子和正文同宽同形，
 * 肉眼读作**重影/描边发糊**，不是"浮起来"。
 *
 * 量化证据（同机同角度截图逐环取样，墨度相对峰值归一）：
 * 正常抗锯齿的墨边在离笔画 4px 处衰减到 0.4% 以下；
 * 加投影后同一量在 4px 处仍有 11%、8px 处 6%、12px 处 3.5%，且右下比左上重一倍
 * ——这正是"影子"被读成"重影"的形状，和位置偏了几像素的副本无从区分。
 *
 * 结论：CJK 大字号的文字投影是重影发生器，靠调小模糊救不回来（调到不重影就看不见），
 * 只能整条通道换掉。静止的立体感改由 [inkVolume] 承担——上缘提亮、下缘压暗，
 * 明暗面画在字形**内部**，结构上不可能产生第二份轮廓。
 */
@Immutable
data class CardDepth(
    /** 相对卡面的高度。0 = 贴在卡面上（不参与视差），正数朝观察者浮起。 */
    val depth: Dp,
    /** 浮雕强度 0..1：字形自身的"上缘受光、下缘背光"（见 [inkVolume]）。 */
    val relief: Float,
)

/**
 * 学期概览卡（以及同类焦点卡）的深度阶梯，由近到远。
 *
 * 名字按**语义**取而不是按字号，这样换字号/换文案时层级关系不会跟着漂。
 */
object CardDepthLadder {
    /** 最大视觉重心：整卡唯一的那枚数字（「第 9 周」）。 */
    val Hero = CardDepth(depth = 10.dp, relief = 1.00f)

    /** 卡片主体标识（课表名「我的课表」）：比数字退一档，但仍在正文之上。 */
    val Title = CardDepth(depth = 7.dp, relief = 0.72f)

    /** 眉标贴纸（学期胶囊）：自带底色，靠位移示层级；不叠明暗，否则糊成双边框。 */
    val Chip = CardDepth(depth = 5.dp, relief = 0.00f)

    /** 数字的量纲说明（「共 20 周」）：跟着数字走，但要明显慢于数字。 */
    val Unit = CardDepth(depth = 3.dp, relief = 0.34f)

    /** 卡面本身：事实类文字与进度条留在这一层，不位移、不出明暗。 */
    val Face = CardDepth(depth = 0.dp, relief = 0f)
}

/**
 * 该层的视差位移（像素）：倾角 → 这层相对卡面漂移多少。
 *
 * 与 [cardTiltParallax] 用的是**同一条公式**（同向、同比例、同一次回正），这里把它抽成
 * 纯函数只是为了能被单测钉住，并让"层间错开多少"这个量可被计算而不是靠肉眼调参。
 *
 * 两个夹取都必须做：
 * - [maxDegrees] 夹到 ≥1，否则除以 0 会让位移变成 NaN；
 * - 角度夹到 ±maxDegrees。回正弹簧 [Motion.TiltReturn] 有约 27% 过冲，
 *   不夹的话过冲瞬间位移会突破"满倾位移 == 该层深度"这条 1:1 的规则，
 *   层与层的比例在那一帧里就乱了。
 *
 * 线性近似（sinθ ≈ θ）是刻意的：17° 处两者相差 1.3%，肉眼不可见，
 * 而线性让"满倾位移 = 该层深度"成立，层间比例可以直接读作前后关系。
 */
internal fun parallaxDrift(
    rotationX: Float,
    rotationY: Float,
    maxDegrees: Float,
    depthPx: Float,
): Offset {
    val safeMax = maxDegrees.coerceAtLeast(1f)
    val pxPerDegree = depthPx / safeMax
    val clampedX = rotationX.coerceIn(-safeMax, safeMax)
    val clampedY = rotationY.coerceIn(-safeMax, safeMax)
    return Offset(x = -clampedY * pxPerDegree, y = clampedX * pxPerDegree)
}

/**
 * 一次调用给出该层的 3D 表现。
 *
 * [CardDepth.depth] 为 0 的层（[CardDepthLadder.Face]）原样返回、不加任何图层——
 * 卡面上本来就该有一半内容完全不参与 3D，否则整张卡又变成"一块会歪的平板"。
 *
 * 位移走 graphicsLayer（只改绘制、不触发重组），与 [cardTiltParallax] 一样留在合成线程上。
 */
fun Modifier.cardTextDepth(
    state: CardTiltState,
    style: CardTiltStyle,
    depth: CardDepth,
): Modifier {
    if (depth.depth <= 0.dp) return this
    val depthPx = depth.depth
    return graphicsLayer {
        val drift =
            parallaxDrift(
                rotationX = state.rotationX.value,
                rotationY = state.rotationY.value,
                maxDegrees = style.maxTiltDegrees,
                depthPx = depthPx.toPx(),
            )
        translationX = drift.x
        translationY = drift.y
    }
}

/** 一次浮雕的三个色阶：上缘受光色 / 本色 / 下缘背光色。 */
@Immutable
data class CardInk(
    val top: Color,
    val mid: Color,
    val bottom: Color,
)

/**
 * 这一层的字形明暗面：光从正上方来，上缘提亮、下缘压暗。
 *
 * 这是**替代文字投影**的静态深度通道。区别是结构性的：投影是"另画一份模糊的字",
 * 明暗面是在同一份字形上换颜色——多亮多暗都在笔画之内，永远不会有第二份轮廓，
 * 因此不可能出现重影（推导见 [CardDepth] 的 kdoc）。
 *
 * 提亮走白、压暗走黑而不是换色相：字色本身已经承载语义（强调色 = 周次，
 * 正文色 = 课表名），只调整明度才不会把主题色抽干。
 *
 * 深色主题反过来多压一点、少提一点：卡面接近于黑，白上加白没有可读的台阶，
 * 只有往下压暗才看得出厚度（与旧版那道文字软影的 dark 分支同一个理由）。
 */
fun CardDepth.inkVolume(
    base: Color,
    dark: Boolean = false,
): CardInk {
    if (relief <= 0f) return CardInk(top = base, mid = base, bottom = base)
    val light = ((if (dark) 0.16f else 0.22f) * relief).coerceIn(0f, 1f)
    val shade = ((if (dark) 0.30f else 0.17f) * relief).coerceIn(0f, 1f)
    return CardInk(
        top = lerp(base, Color.White, light),
        mid = base,
        bottom = lerp(base, Color.Black, shade),
    )
}

/**
 * 明暗面的分界：受光带压在上部 [BEVEL_MID_STOP] 之内，其余 45% 交给背光带。
 *
 * 取 0.55 而不是 0.5 是刻意的——CJK 字形的重心（横画的视觉基线）比几何中线略低，
 * 分界点跟着下移，中段回落到本色的位置才落在"看起来像字身中间"的地方。
 */
private const val BEVEL_MID_STOP = 0.55f

/** 把 [CardInk] 变成自上下行的渐变笔芯；中段回落到本色，让主色仍是主色。 */
fun CardInk.toBrush(): Brush =
    Brush.verticalGradient(
        0f to top,
        BEVEL_MID_STOP to mid,
        1f to bottom,
    )
