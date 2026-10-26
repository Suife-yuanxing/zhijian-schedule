package com.zhijian.schedule.core.ui.island

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 对比度计算与「安全色求解」。
 *
 * 为什么浮岛需要自己一套求解，而不是直接复用页面里的 textMuted：
 * 页面正文压的底是 [CourseColors.bg]（浅色主题接近白但带纸色），而浮岛压的底是
 * [CourseColors.surface]（更亮）或玻璃主题下更亮的底层——同一个 muted 换个底就掉档。
 * 实测「纸本暖白」未选中标签 3.80、「清爽极简」2.99，都低于正文门槛 4.5。
 *
 * 所以浮岛用「给定量」反解：以主题色为起点，只在浮岛这一层把颜色往能达标的方向推，
 * 不动全局 textMuted，避免把正文整体压黑。
 */
object Contrast {

    /** sRGB 通道（0~1，已含 gamma）→ 线性光强 */
    private fun linearize(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG 相对亮度（0 = 黑，1 = 白） */
    fun luminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    /** 两色对比度，范围 1.0~21.0；正文门槛 4.5，图形/大字门槛 3.0 */
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * alpha 合成：把 [fg] 以 [alpha] 压到 [bg] 上，得到实际看到的颜色。
     *
     * 在 sRGB（gamma）空间做线性插值——与 GPU 默认的 SRC_OVER 混合一致，
     * 这也是用户在屏幕上真正看到的颜色，比在线性空间合成更贴近实机。
     */
    fun composite(fg: Color, bg: Color, alpha: Float): Color {
        val a = alpha.coerceIn(0f, 1f)
        return Color(
            red = fg.red * a + bg.red * (1f - a),
            green = fg.green * a + bg.green * (1f - a),
            blue = fg.blue * a + bg.blue * (1f - a),
            alpha = 1f,
        )
    }

    /** 整体缩放 RGB（保持色相与饱和度），用于「同色相压深/提亮」 */
    private fun scale(color: Color, factor: Float): Color = Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha,
    )

    /**
     * 以 [target] 为方向做线性插值，直到 [base] 压在 [under] 上达到 [minRatio]。
     *
     * 用于「未选中标签」：从 textMuted 往 text 方向混，混到刚好达标就停——
     * 达标即停很关键，多混一步就会让次要信息抢走主信息的视觉权重。
     */
    fun mixUntil(
        base: Color,
        target: Color,
        under: Color,
        minRatio: Double,
        steps: Int = 50,
    ): Color {
        if (ratio(base, under) >= minRatio) return base
        var best = target
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val c = Color(
                red = base.red + (target.red - base.red) * t,
                green = base.green + (target.green - base.green) * t,
                blue = base.blue + (target.blue - base.blue) * t,
                alpha = base.alpha,
            )
            if (ratio(c, under) >= minRatio) return c
            best = c
        }
        return best
    }

    /**
     * 把 [base] 以 [alpha] 压在 [under] 上之后，使其与 [fg] 的对比度达到 [minRatio]。
     *
     * 先试「压深」（绝大多数主题的白字压彩底都靠这一步），压不动再试「提亮」
     * （应付 onAccent 本身是深色的主题，例如液态玻璃暗色档的深蓝字）。
     * 返回的是**未合成**的底色，调用方仍按原 alpha 铺上去。
     */
    fun adjustUntil(
        base: Color,
        under: Color,
        fg: Color,
        alpha: Float,
        minRatio: Double,
    ): Color {
        if (ratio(fg, composite(base, under, alpha)) >= minRatio) return base
        // 压深：每步 1.5%，最多压到原色的 40%
        for (i in 1..40) {
            val c = scale(base, 1f - i * 0.015f)
            if (ratio(fg, composite(c, under, alpha)) >= minRatio) return c
        }
        // 提亮：每步 1.5%，最多提到原色的 1.6 倍
        for (i in 1..40) {
            val c = scale(base, 1f + i * 0.015f)
            if (ratio(fg, composite(c, under, alpha)) >= minRatio) return c
        }
        return base
    }
}
