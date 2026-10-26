package com.zhijian.schedule.core.ui
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 触摸点 → 以卡片中心为原点的 [-1,1]（x 右正、y 下正）。
 * 手指拖出卡片后夹到边缘，倾角不会继续无界增大。
 */
internal fun tiltNormalized(position: Offset, size: Size): Offset {
    if (size.width <= 0f || size.height <= 0f) return Offset.Zero
    val nx = (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
    val ny = (position.y / size.height * 2f - 1f).coerceIn(-1f, 1f)
    return Offset(nx, ny)
}

/**
 * 与 vanilla-tilt 同向（该库 `tiltX = max - x·max·2`、`tiltY = y·max·2 - max`，
 * 再以 `rotateX(tiltY) rotateY(tiltX)` 施加）：**指腹压住的那一角朝观看者抬起来**。
 * 右侧下压时 rotateY 为负，下侧下压时 rotateX 为正。
 */
internal fun tiltAngles(normalized: Offset, maxDegrees: Float): TiltAngles {
    val max = maxDegrees.coerceAtLeast(0f)
    val nx = normalized.x.coerceIn(-1f, 1f)
    val ny = normalized.y.coerceIn(-1f, 1f)
    return TiltAngles(
        rotationX = ny * max,
        rotationY = -nx * max,
    )
}

internal data class TiltAngles(val rotationX: Float, val rotationY: Float)

/**
 * 反光的几何：全部由纯函数给出，便于单测钉住（见 `CardTiltTest`）。
 *
 * [sheenStrength] 是 0..1 的"倾斜程度"——指腹到卡心的距离除以半对角线，
 * 卡正中心为 0（不倾斜则没有定向反射），四角为 1。
 */
internal data class GlareGeometry(
    val center: Offset,
    val broadRadius: Float,
    val coreRadius: Float,
    val sheenStart: Offset,
    val sheenEnd: Offset,
    val sheenStrength: Float,
)

/**
 * 把指腹位置换算成三层光的几何。
 *
 * 半径用 `farthest-corner`（指腹到四个角里最远的那个）而不是卡面尺寸的固定倍率：
 * 前者在任何落点都刚好盖满卡面，后者在贴边时会露出渐变的硬边。
 */
internal fun glareGeometry(position: Offset, size: Size): GlareGeometry {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) {
        return GlareGeometry(Offset.Zero, 1f, 1f, Offset.Zero, Offset.Zero, 0f)
    }
    val center = Offset(
        x = position.x.coerceIn(0f, width),
        y = position.y.coerceIn(0f, height),
    )
    val corners = listOf(
        Offset.Zero,
        Offset(width, 0f),
        Offset(0f, height),
        Offset(width, height),
    )
    val broadRadius = corners
        .maxOf { (center - it).getDistance() }
        .coerceAtLeast(1f)

    val longSide = max(width, height)
    val shortSide = min(width, height)
    val coreRadius = (shortSide * 0.58f).coerceAtLeast(longSide * 0.30f)

    val dx = center.x - width / 2f
    val dy = center.y - height / 2f
    val distance = Offset(dx, dy).getDistance()
    // 扫光强度直接复用倾角那一套归一化：指腹压到边上时卡片正好倾到最大角，反射也最亮；
    // 卡心倾角为 0，就不该有定向反射。这样"看起来有多斜"和"反光有多强"永远同一把尺子。
    val normalized = tiltNormalized(center, size)
    val sheenStrength = max(abs(normalized.x), abs(normalized.y))
    val direction = if (distance < 1e-3f) Offset(1f, 0f) else Offset(dx / distance, dy / distance)
    // 光带总长取 2 × 1.25 × 最长边：光带过卡心沿任意方向都能横穿整张卡
    val reach = longSide * 1.25f

    return GlareGeometry(
        center = center,
        broadRadius = broadRadius,
        coreRadius = coreRadius,
        sheenStart = center - direction * reach,
        sheenEnd = center + direction * reach,
        sheenStrength = sheenStrength,
    )
}
