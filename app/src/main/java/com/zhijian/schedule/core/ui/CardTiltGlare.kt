package com.zhijian.schedule.core.ui
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * 径向反射流光：跟着触摸点走的三层光，全部用 [BlendMode.Screen] 只提亮。
 *
 * 层叠依据是业界最成熟的"全息卡"实现（simeydotme/pokemon-cards-css 的 `.card__glare`）：
 * 它的高光是 `radial-gradient(farthest-corner circle at <指针>)`，即**半径取指腹到最远角的距离**，
 * 这样无论手指按在哪里，光都刚好铺满卡面而不会露出边界。本实现照此取 [GlareGeometry.broadRadius]。
 *
 * 三层分工（都是同一个 [BlendMode.Screen]，亮度可控地叠加）：
 * 1. 外层柔光：farthest-corner 半径，铺满卡面 → "整张纸被照亮"；
 * 2. 定向扫光：一条过指腹、**垂直于「卡心→指腹」方向**的软光带 → 这才是"流光"。
 *    vanilla-tilt 的高光正是一条被旋转过的定向渐变（`linear-gradient` + `rotate(angle)`，
 *    angle = atan2(指针 - 卡心)），本实现用同一套角度、换成有限宽度的光带而不是半平面洗白；
 *    强度随离中心距离增长——卡心不倾斜就不该有反射，越靠边越斜、反射越亮；
 * 3. 内层亮核：贴着指腹的镜面高光。
 *
 * 必须排在 `.background(...)` 之后、`.border(...)` / 文字之前：
 * 这样亮斑只提亮卡面，不会盖在课名上影响可读性。
 *
 * **颜色不是纯白 + 纯黑**：彩色卡面上，白光会把色相洗成一团白斑（像过曝），
 * 黑罩会把颜色抽干发灰（绿色底压成脏橄榄）。所以 [highlight] 与 [shade] 由调用方
 * 传入**同色相**的明暗色调（见 `CourseTone.highlight` / `CourseTone.shade`）：
 * 反射的是这张纸自己的颜色，暗面是同一张纸的暗面。中性卡面传默认值即可。
 */
fun Modifier.cardTiltGlare(
    state: CardTiltState,
    shape: Shape,
    style: CardTiltStyle,
    /** 受光侧的颜色；默认纯白，彩色卡面传该卡片的同色相浅调 */
    highlight: Color = Color.White,
    /** 背光侧的颜色；默认纯黑，彩色卡面传该卡片的同色相深调 */
    shade: Color = Color.Black,
): Modifier =
    drawBehind {
        // press 用弹簧加压，会冲过 1 再收口（见 Motion.PressIn）；这里夹住，
        // 免得 alpha 越界（>1 的 alpha 在部分混合模式下会被截断成 1，但语义上仍是错的）。
        val intensity = state.press.value.coerceIn(0f, 1f)
        if (intensity <= 0.01f) return@drawBehind
        val position = state.touch.value
        if (!position.isSpecified) return@drawBehind

        val glare = glareGeometry(position, size)
        val clip =
            Path().apply {
                addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
            }
        val peak = style.glareAlpha * intensity

        clipPath(clip) {
            // 0) 背光压暗：让光斑周围"暗下去"一圈，光才浮得起来。
            //    浅色卡面（≈0.98）上 Screen 叠白最多只能 +3 级亮度，肉眼读不出；
            //    业界全息卡（pokemon-cards-css 的 .card__glare）正是靠渐变尾巴的黑色做出落差。
            //    Multiply 只压暗、不染色，卡面底色与文字对比不会被改歪。
            val shadeAlpha = style.glareShade * intensity
            if (shadeAlpha > 0.005f) {
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to shade.copy(alpha = shadeAlpha),
                            center = glare.center,
                            radius = glare.broadRadius,
                        ),
                    radius = glare.broadRadius,
                    center = glare.center,
                    blendMode = BlendMode.Multiply,
                )
            }

            // 1) 外层柔光：半径刚好到最远角，铺满卡面
            drawCircle(
                brush =
                    Brush.radialGradient(
                        0f to highlight.copy(alpha = peak * BROAD_PEAK),
                        0.5f to highlight.copy(alpha = peak * BROAD_MID),
                        1f to Color.Transparent,
                        center = glare.center,
                        radius = glare.broadRadius,
                    ),
                radius = glare.broadRadius,
                center = glare.center,
                blendMode = BlendMode.Screen,
            )

            // 2) 定向扫光：垂直于「卡心→指腹」，强度随离中心的距离增长
            val sheen = peak * SHEEN_PEAK * glare.sheenStrength
            if (sheen > 0.01f) {
                drawRect(
                    brush =
                        Brush.linearGradient(
                            SHEEN_OUTER to Color.Transparent,
                            SHEEN_INNER to Color.Transparent,
                            SHEEN_CENTER to highlight.copy(alpha = sheen),
                            SHEEN_INNER_MIRROR to Color.Transparent,
                            SHEEN_OUTER_MIRROR to Color.Transparent,
                            start = glare.sheenStart,
                            end = glare.sheenEnd,
                        ),
                    topLeft = Offset.Zero,
                    size = size,
                    blendMode = BlendMode.Screen,
                )
            }

            // 3) 内层亮核：贴着指腹的镜面高光
            drawCircle(
                brush =
                    Brush.radialGradient(
                        0f to highlight.copy(alpha = peak * CORE_PEAK),
                        0.25f to highlight.copy(alpha = peak * CORE_MID),
                        0.6f to highlight.copy(alpha = peak * CORE_TAIL),
                        1f to Color.Transparent,
                        center = glare.center,
                        radius = glare.coreRadius,
                    ),
                radius = glare.coreRadius,
                center = glare.center,
                blendMode = BlendMode.Screen,
            )
        }
    }

// 三层光的峰值系数：卡心处只有柔光 + 亮核（合计 0.94·peak，与单层实现同亮度），
// 越靠边扫光越强，最亮处约 1.4·peak——倾斜越狠反射越亮，是物理上也讲得通的走向。
private const val BROAD_PEAK = 0.26f
private const val BROAD_MID = 0.11f
private const val SHEEN_PEAK = 0.50f
private const val CORE_PEAK = 0.68f
private const val CORE_MID = 0.44f
private const val CORE_TAIL = 0.16f

// 光带剖面：0.44~0.56 是亮区，两侧各留一段透明缓冲，得到一条边界柔和的"扫光"。
private const val SHEEN_OUTER = 0f
private const val SHEEN_INNER = 0.44f
private const val SHEEN_CENTER = 0.50f
private const val SHEEN_INNER_MIRROR = 0.56f
private const val SHEEN_OUTER_MIRROR = 1f
