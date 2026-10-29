package com.zhijian.schedule.core.ui
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max

/**
 * 拉伸上限与手柄直径的比值：0.375（即 24dp 手柄最多拉长到 33dp）。
 *
 * 为什么是比例而不是固定 dp：手柄会在 16↔24 之间变，固定 9dp 会让小圆点被拉成
 * 一根细条（16→25dp，+56%）、大手柄却只 +37%，同一个动作在两端的手感不一致。
 * 按比例取，两端都是 +37.5% 的相对伸长，读作同一种「被拽长」。
 */
internal const val JELLY_STRETCH_RATIO = 0.375f

/** 拉伸时的最大纵向压缩比例：体积守恒的错觉，宽了就要矮一点（24 → 17.8dp）。 */
internal const val JELLY_SQUASH = 0.26f

/**
 * 按住时的抓取形变：占拉伸上限的 45%。
 *
 * 按在 24dp 手柄上时宽度 24 → 28.1（+17%），与 M3 规范按下档的 28dp 几乎重合
 * （`SwitchTokens`：Unselected 16 / Selected 24 / Pressed 28）；按在 16dp 小圆点上时
 * 16 → 18.7，同样是 +17%。
 *
 * 为什么不是 22%（本组件的上一版）：那只是拉伸上限的 22%，落到直径上只有 +8%，
 * 手指按住时几乎看不出「被捏住」，等于白留了一条通道。
 */
private const val JELLY_GRAB = 0.45f

/**
 * 速度归一化基准：`spring(ζ=0.55, k=420)` 走完单位行程（0→1）时的**峰值速度**。
 *
 * 推导：ω = √420 ≈ 20.49，ω_d = ω√(1-ζ²) ≈ 17.12，
 * v(t) = (ω²/ω_d)·e^(-ζωt)·sin(ω_d·t)，极值落在 t* = atan(ω_d/ζω)/ω_d ≈ 57.8ms，
 * 代入得 v_peak ≈ 10.69 /s（逐帧数值积分复核：10.74）。行程固定是 0↔1
 * （中心始终从 16 走到 36，与手柄直径无关，见 [jellyThumbRect]），所以这一个常数够用；
 * 快速连点时弹簧被中途改向，速度可能超过它，由 [jellyStretch] 夹紧。
 */
private const val JELLY_SPEED_REF = 10.69f

/**
 * 把「速度 + 按住程度」折算成 0..1 的拉伸量。
 *
 * 速度取绝对值：往左、往右都是被拉长，形变不该分方向。速度归一化后与按住量相加再夹紧，
 * 所以松手瞬间（速度最大、按住量归零）不会出现拉伸量掉档的顿挫。
 */
internal fun jellyStretch(
    speed: Float,
    grab: Float = 0f,
): Float {
    val fromSpeed = (abs(speed) / JELLY_SPEED_REF).coerceIn(0f, 1f)
    val fromGrab = grab.coerceIn(0f, 1f) * JELLY_GRAB
    return (fromSpeed + fromGrab).coerceIn(0f, 1f)
}

/**
 * 果冻手柄在轨道坐标系里的矩形（px）。
 *
 * @param track 轨道尺寸
 * @param progress 0 = 左端，1 = 右端；弹簧过冲时会略微越界（< 0 或 > 1）。
 * @param stretch 0 = 正圆，1 = 拉满（宽 +maxStretch，高按体积守恒压到约 74%）
 * @param diameter 当前直径（随选中态在 16↔24dp 之间变）
 * @param maxStretch 拉伸时额外长出的最大宽度（按 [JELLY_STRETCH_RATIO] 与直径成比例）
 * @param squash 拉伸时的最大纵向压缩比例
 *
 * 中心轨迹是 `TrackHeight/2 + (TrackWidth - TrackHeight) × progress`：因为
 * `travel = W - d - (H - d) = W - H`，**行程恒为 20dp、与直径无关**——手柄变大时
 * 只是内边距从 8dp 收到 4dp，中心路径不动。这正是 M3 手柄生长时不会「走歪」的原因。
 *
 * 夹紧只作用于**轨道边界**：囊形顶到边缘时超出的部分被压回去（宽度收窄），
 * 同时按面积守恒把高度补回来，所以「到站」读到的是一次挤压，而不是缩水。
 */
internal fun jellyThumbRect(
    track: Size,
    progress: Float,
    stretch: Float,
    diameter: Float,
    maxStretch: Float,
    squash: Float,
): Rect {
    val s = stretch.coerceIn(0f, 1f)
    val inset = (track.height - diameter) / 2f
    val travel = (track.width - diameter - inset * 2f).coerceAtLeast(0f)
    val width = diameter + maxStretch * s
    val centerX = inset + diameter / 2f + travel * progress
    val centerY = track.height / 2f
    val left = (centerX - width / 2f).coerceAtLeast(0f)
    val right = (centerX + width / 2f).coerceAtMost(track.width).coerceAtLeast(left)
    // 线性压缩是「调过的手感上限」，面积守恒是「物理下限」：取较大者。
    // 宽度没被夹时线性项更大（手感与上一版一致）；被夹窄时守恒项接管，高度补回来。
    val drawnWidth = (right - left).coerceAtLeast(1e-3f)
    val linear = diameter * (1f - squash * s)
    val volume = (diameter * diameter) / drawnWidth
    val height = max(linear, volume).coerceIn(1e-3f, track.height)
    return Rect(
        left = left,
        top = centerY - height / 2f,
        right = right,
        bottom = centerY + height / 2f,
    )
}
