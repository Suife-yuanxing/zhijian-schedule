package com.zhijian.schedule.core.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/** 主题相关的强度：玻璃主题反光更亮、抬得更高；浅色纸面需要更高的白才能看见。 */
data class CardTiltStyle(
    val maxTiltDegrees: Float,
    val glareAlpha: Float,
    /** 按下时整卡放大的比例（1.012 = 放大 1.2%）。 */
    val liftScale: Float,
    /** 按下时抬离"桌面"的高度，决定投影的浓度。 */
    val liftElevation: Dp,
    /**
     * 高光**背光侧**的压暗强度。
     *
     * 浅色卡面的亮度本来就接近 1.0（实测 250/255 ≈ 0.98），`BlendMode.Screen` 叠白光
     * 在数学上只能推到 ~0.99——实测平均提亮仅 +0.66 级、肉眼读不出来。
     * 业界全息卡的做法正是这样：simeydotme/pokemon-cards-css 的 `.card__glare` 用
     * `radial-gradient(最远角 at 指针, 白 0.8 @10%, 白 0.65 @20%, **黑 0.5 @90%**)`，
     * 尾巴是**黑**的——真实光泽之所以在浅色纸面上可见，靠的是被照亮区周围那圈"背光"的明暗落差，
     * 而不是把白再叠加到白上。
     *
     * 所以这里补一层随距离加深的柔和压暗（Multiply），让光斑"浮"起来。
     * 深色主题取一个很小的值（Screen 在暗底上本来就够亮，压重了显脏）。
     */
    val glareShade: Float,
    /**
     * 透视强度：**有效相机距离 = 卡片最长边 × 本值**。
     *
     * **越小透视越猛**——react-parallax-tilt 的文档原话是
     * "perspective ... The lower the more extreme the tilt gets"。
     *
     * 关键坑：Compose 的 `cameraDistance` 单位**不是像素**。本机实测
     * （1080×2376 @480dpi，`.tmp/cal_sweep.sh`）：传入值 v 对应的有效相机距离约为
     * `v × 64`。早期版本按「像素」直接传 `最长边 × 1.6 = 1536`，等效相机距离约 98,000px，
     * 等于正交投影——实测近边外溢只有 0%，画面只有"斜切 + 缩小"，读不出任何立体。
     * 这才是"倾动不够夸张"的真正原因。现在按 [CAMERA_DISTANCE_UNIT] 换算，
     * 本值直接读作「相机距离是卡片最长边的几倍」。
     *
     * 别低于 1.1：再小远端会被压到相机后面，卡片会撕裂。
     */
    val perspectiveScale: Float,
)

@Composable
fun rememberCardTiltStyle(): CardTiltStyle {
    // 金属主题：卡面是抛光金属板，反光更强、抬得更高、倾角更狠（金属比纸更"硬"）
    val metal = LocalCoursePreset.current.metal != null
    val dark = LocalIsDark.current
    return remember(metal, dark) {
        if (metal) {
            return@remember CardTiltStyle(
                maxTiltDegrees = 16f,
                glareAlpha = if (dark) 0.58f else 0.76f,
                liftScale = 0.038f,
                liftElevation = 34.dp,
                glareShade = if (dark) 0.14f else 0.22f,
                perspectiveScale = 1.38f,
            )
        }
        CardTiltStyle(
            // 业界网页卡片的默认倾角：beUI 12°、vanilla-tilt 15°、react-parallax-tilt 20°。
            // 那些是几百像素的小卡；本项目动的是整宽约 320dp 的焦点卡，同角度畸变更大，
            // 取 13°（玻璃 14°）——落在业界区间中下段，但比最初那版 8~9° 明显"活"得多。
            maxTiltDegrees = 13f,
            // 反光强度：不必顶到 1.0。实测 1.0 时近指腹处被推到 (248,251,245)，
            // 等于把课程色洗成白纸——"被照亮"应该看得见底色，而不是把底色擦掉。
            glareAlpha =
                if (dark) 0.45f else 0.62f,
            // 抬升对齐 Apple FOCUS-01（聚焦项放大 1.05~1.1 + 投影抬升）。那个量级是给
            // 10 英尺外的大屏卡片；手机上压到 2.8~3.4%，是"被指腹抬起来"里偏夸张的一档。
            liftScale = 0.028f,
            liftElevation = 24.dp,
            // 暗面强度：同色相的深调比纯黑压得"轻"，所以从这里补回来。
            // 明暗落差是「光浮起来」的关键（拾色器实测约 30 级）。
            glareShade = if (dark) 0.10f else 0.18f,
            // 相机距离取 1.45 倍卡长：网页惯例是 perspective 1000px 配 300px 小卡（3.3 倍），
            // 这里更近，倾斜时四角"翘"得更狠。见字段 kdoc 的换算说明。
            perspectiveScale = 1.45f,
        )
    }
}

/**
 * 焦点大卡（整宽、字号最大、整页只该有一张的那块卡面）的倾动参数。
 *
 * 「我的」页学期概览卡与今日页「正在上课」卡共用这一份取值——两块卡面积接近、
 * 可动范围接近，只有共用一个工厂，「复刻」才是同一套手感而不是两次调参的相似值。
 *
 * 相对 [rememberCardTiltStyle] 的课块/小卡基准：倾角 13~14° → 17°（面积越大越吃角度）、
 * 反光 ×1.05（上限顶到 1.0 满不透明）、抬升 ×1.2 且多 10dp（大卡的投影半径更大，
 * 同样的高度在大卡上会显得没抬起来）。
 *
 * 透视反而**放宽**到 1.70 倍卡长（比基准的 1.45 更远）：本卡宽 960px、屏宽 1080px，
 * 左右各只剩 60px 余量，17° 下相机再近一点近边就会顶出屏幕被裁。
 * 所以大卡靠"更大的倾角"补戏剧性，不靠"更近的相机"——两者在这里是互斥的。
 */
@Composable
fun rememberFocusCardTiltStyle(): CardTiltStyle {
    val base = rememberCardTiltStyle()
    return remember(base) { focusTiltStyle(base) }
}

/** [rememberFocusCardTiltStyle] 的纯函数内核：取值可单测，两块焦点卡不可能各调各的。 */
internal fun focusTiltStyle(base: CardTiltStyle): CardTiltStyle =
    base.copy(
        maxTiltDegrees = 17f,
        glareAlpha = (base.glareAlpha * 1.05f).coerceAtMost(1f),
        liftScale = base.liftScale * 1.2f,
        liftElevation = base.liftElevation + 10.dp,
        perspectiveScale = 1.70f,
    )
