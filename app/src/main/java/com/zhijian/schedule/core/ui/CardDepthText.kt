package com.zhijian.schedule.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡面文字的「深度层级」：这一行浮在卡面上多高、静止时压出多深的影。
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
 *    那些场景里卡片会退回成一张纸。所以每层再配一道"光从上方来"的浮雕软影
 *    （[reliefShadow]），静止也读得出谁在前。
 *
 * ## 取值依据
 *
 * - 层级不是"字号越大浮得越高"，而是**视觉重心**决定：素材站 parallax-title-card 的
 *   三层取 1.05em（eyebrow）/ 0.54em（大标题）/ 0.62em（副标），小字反而最靠前。
 *   本卡的最大视觉重心是「第 9 周」这枚数字，所以它占最高层；眉标（学期胶囊）次之，
 *   带单位的说明（共 20 周）再靠后，事实类信息（开学日期、进度条、页码）留在卡面。
 * - 正文留在 Z=0 不是偷懒：3D-immersive 规范 §3 明确写着"倾斜/抬起的平面上小字会有
 *   亚像素发虚，长文本必须留在卡面，只有短的大标题/标签可以前移"。
 * - 幅度：焦点大卡最大倾角 17°（[rememberFocusCardTiltStyle]），最高层 6dp 在满倾时
 *   漂移 6dp≈16px；层间差 6 / 4 / 3 / 1.5 / 0 已经能读出"错开"，再大就会撞到右侧
 *   绩点块或挤进下一行——戏剧性交给层间比例，不靠绝对值。
 *
 * ## 试过但被否掉的做法：动态投影
 *
 * 曾按"影子钉在卡面、字形漂走"的思路，在倾动时用 saveLayer + tint + BlurMaskFilter
 * 画一份偏移的字形副本。真机截图否掉了它：整字复本哪怕模糊到半径是偏移量的两倍，
 * 读到的仍是"重影/串色"而不是"影子"——因为它有和正文一样的笔画锐度基线。
 * 真实软阴影是**距离场**而不是"一份模糊的字"，用逐帧 saveLayer 硬凑既费合成又不像，
 * 所以只保留 [reliefShadow] 这条静态通道。
 */
@Immutable
data class CardDepth(
    /** 相对卡面的高度。0 = 贴在卡面上（不参与视差），正数朝观察者浮起。 */
    val depth: Dp,
    /** 浮雕强度 0..1：光从上方来时，字形下缘压出的软影（见 [reliefShadow]）。 */
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

    /** 眉标贴纸（学期胶囊）：自带底色，靠位移示层级；不叠浮雕，否则糊成双边框。 */
    val Chip = CardDepth(depth = 5.dp, relief = 0.00f)

    /** 数字的量纲说明（「共 20 周」）：跟着数字走，但要明显慢于数字。 */
    val Unit = CardDepth(depth = 3.dp, relief = 0.34f)

    /** 卡面本身：事实类文字与进度条留在这一层，不位移、不出影。 */
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

/** 影子横纵比：光从正上方来时，软影只向下方偏移，横向给纵向的一半。 */
private const val SHADOW_SKEW_X = 0.5f

/**
 * 静止时的浮雕投影：光从上方来，字形下缘有一道同色相的软影。
 *
 * 参数都是"按 [CardDepth.relief] 缩放过的 dp"，乘 [Density.density] 换成 px 再交给
 * [Shadow]（它的 offset / blurRadius 单位是像素，不是 dp——直接喂 dp 在高密度屏上会
 * 细到看不见，这是 Compose 文本阴影最常见的坑）。
 *
 * 为什么影子取**同色相深调**而不是黑：见 [cardTiltGlare] 与 CourseTone.shade 的注释——
 * 黑乘在彩色底上会把颜色抽干发灰；深色主题下纯黑也压不出层次（卡面本身就接近黑）。
 */
fun CardDepth.reliefShadow(
    ink: Color,
    density: Density,
    dark: Boolean = false,
): Shadow? {
    if (relief <= 0f) return null
    val lift = (if (dark) 1.3f else 2.1f) * relief
    val blur = (if (dark) 3.0f else 4.2f) * relief
    val alpha = (if (dark) 0.44f else 0.34f) * relief
    // 影子朝"下方"偏：横向只给纵向的一半（1:2），这是"光从正上方来"在平面上的投影走向。
    // 三处都乘 density 换成像素——Shadow 的 offset / blurRadius 单位是 px 而不是 dp。
    return Shadow(
        color = ink.copy(alpha = alpha.coerceIn(0f, 1f)),
        offset = Offset(SHADOW_SKEW_X * lift * density.density, lift * density.density),
        blurRadius = blur * density.density,
    )
}
