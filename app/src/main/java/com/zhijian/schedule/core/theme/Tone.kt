package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 课程色 → 同色系色调（tone）三元组。
 *
 * 旧做法是「原色 × 14% alpha 铺底 + 左色条」：八种色相被透明度洗成 0.003~0.027 的彩度，
 * 紫色 #9B7DB8 的填充色相算出来甚至落回 0°（红），八门课看起来只有三种浅橙。
 *
 * 新做法是 M3 的 container / on-container 思路：保留源色相 H，只在 OKLCH 空间里换 L 与 C，
 * 得到 [container]（铺底）、[line]（1dp 描边）、[ink]（正文）、[inkMuted]（次要信息）。
 * 对比度恒定：亮色 ink/container ≈ 8:1，inkMuted/container ≈ 6:1；暗色 ink/container ≈ 10:1。
 */
data class CourseTone(
    val container: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    /**
     * 受光高光：卡面被照亮时用的颜色。
     *
     * 不用纯白：Screen 混合下白光会把色相洗平，卡面变成"一团白斑"——实测旧实现近指腹处
     * 被推到 (248,251,245)、通道比趋向 1.0，绿色课直接褪成灰白，像过曝而不像反光。
     * 这里取**同色相的浅调**。注意：浅色卡面的高光本就带不动多少彩度（L 0.97 处几乎没有
     * 可用色域，物理上浅纸面的镜面反射也接近白色），所以真正治"过曝"的是
     * `CardTiltStyle.glareAlpha` 的强度上限，色调只是让它不发灰。
     */
    val highlight: Color,
    /**
     * 暗面：卡面背光侧压暗时用的颜色。
     *
     * 不用纯黑：黑色乘在彩色底上会把颜色抽干、发灰发脏（绿色卡面直接变"脏橄榄"）。
     * Material 3 的立场是明暗层级应该走**同色相的色调阶梯**而不是叠黑，
     * 设计系统里也普遍采用「带彩的阴影」。所以这里取同色相的深色调。
     */
    val shade: Color,
)

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float {
    val x = c.coerceIn(0f, 1f)
    return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
}

/** 源色的 OKLCH 色相与彩度。彩度过低时色相不可靠，返回 null 走中性色。 */
private fun hueAndChromaOf(color: Color): Pair<Float, Float>? {
    val r = srgbToLinear(color.red)
    val g = srgbToLinear(color.green)
    val b = srgbToLinear(color.blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    val chroma = hypot(a, bb)
    if (chroma < 0.012f) return null
    var h = atan2(bb, a) * 180f / PI.toFloat()
    if (h < 0f) h += 360f
    return h to chroma
}

/** OKLCH → sRGB，超出色域返回 null（由调用方降彩度重试） */
private fun oklchOrNull(l: Float, c: Float, h: Float): Color? {
    val rad = h * (PI.toFloat() / 180f)
    val a = c * cos(rad)
    val b = c * sin(rad)
    val l1 = l + 0.3963377774f * a + 0.2158037573f * b
    val m1 = l - 0.1055613458f * a - 0.0638541728f * b
    val s1 = l - 0.0894841775f * a - 1.2914855480f * b
    val lc = l1 * l1 * l1
    val mc = m1 * m1 * m1
    val sc = s1 * s1 * s1
    val r = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
    val g = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
    val bl = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
    if (r < -0.002f || r > 1.002f) return null
    if (g < -0.002f || g > 1.002f) return null
    if (bl < -0.002f || bl > 1.002f) return null
    return Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
}

/** 逐步降彩度直到落回 sRGB 色域，保留 L 与 H */
private fun tonal(l: Float, c: Float, h: Float): Color {
    if (c <= 0f) return oklchOrNull(l, 0f, 0f) ?: Color.Black
    var chroma = c
    repeat(16) {
        oklchOrNull(l, chroma, h)?.let { return it }
        chroma *= 0.86f
    }
    return oklchOrNull(l, 0f, 0f) ?: Color.Black
}

/**
 * 由课程源色派生色调。暗色模式换一组 L/C，色相始终跟随课程身份。
 */
fun courseTone(source: Color, isDark: Boolean): CourseTone {
    val oklch = hueAndChromaOf(source)
    if (oklch == null) {
        // 中性色（灰/白/黑）：只走明度阶梯，避免算出无意义的色相
        return if (isDark) {
            CourseTone(
                container = tonal(0.285f, 0f, 0f),
                line = tonal(0.400f, 0f, 0f),
                ink = tonal(0.935f, 0f, 0f),
                inkMuted = tonal(0.800f, 0f, 0f),
                highlight = tonal(0.980f, 0f, 0f),
                shade = tonal(0.170f, 0f, 0f),
            )
        } else {
            CourseTone(
                container = tonal(0.965f, 0f, 0f),
                line = tonal(0.855f, 0f, 0f),
                ink = tonal(0.400f, 0f, 0f),
                inkMuted = tonal(0.475f, 0f, 0f),
                highlight = tonal(1.000f, 0f, 0f),
                shade = tonal(0.300f, 0f, 0f),
            )
        }
    }
    val (hue, chroma) = oklch
    // 彩度按源色强度缩放：近中性的蓝灰 #8A8F98 不该被拉成一块亮蓝，
    // 而饱和的陶土 #C4704F 要保留完整的色相辨识度。
    val scale = (chroma / 0.09f).coerceIn(0.12f, 1f)
    return if (isDark) {
        CourseTone(
            container = tonal(0.285f, 0.045f * scale, hue),
            line = tonal(0.400f, 0.060f * scale, hue),
            ink = tonal(0.935f, 0.035f * scale, hue),
            inkMuted = tonal(0.800f, 0.040f * scale, hue),
            // 暗色主题：卡面本身够暗（L 0.285），受光侧还有彩度可用，
            // 光斑是"这张卡自己的亮面"，不是叠上去的一块白
            highlight = tonal(0.960f, 0.050f * scale, hue),
            // 暗面必须比 0.285 的卡面更暗，否则 Multiply 压不下去
            shade = tonal(0.170f, 0.070f * scale, hue),
        )
    } else {
        CourseTone(
            container = tonal(0.945f, 0.028f * scale, hue),
            line = tonal(0.855f, 0.060f * scale, hue),
            ink = tonal(0.400f, 0.110f * scale, hue),
            inkMuted = tonal(0.475f, 0.085f * scale, hue),
            // 亮色主题：高光取 0.97 明度的同色相浅调。
            // 不追求"受光侧更饱和"——那个量级在浅色卡面上取不到（见字段 kdoc）；
            // 它保证的是光斑不变成一块中性白，与卡片是同一个色族。
            highlight = tonal(0.970f, 0.055f * scale, hue),
            // 暗面用同色相的**深饱和**调，而不是把 ink 掺黑：
            // 掺黑会连彩度一起降下去，压在彩色卡面上仍然是一片灰
            shade = tonal(0.300f, 0.095f * scale, hue),
        )
    }
}
