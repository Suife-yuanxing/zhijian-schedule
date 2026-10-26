package com.zhijian.schedule.feature.schedule.components
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** fontScale = 1.0 时的基准尺寸；实际渲染用 [GridMetrics] */
object GridDims {
    /** 时间轴：放「第几大节 + 起止时间」，60dp 是能容下 08:20–10:00 的最窄宽度 */
    val TIME_AXIS_WIDTH: Dp = 60.dp
    val HEADER_HEIGHT: Dp = 56.dp
    val SECTION_HEIGHT: Dp = 46.dp
    /** 时段标题（上午 / 下午 / 晚上）行高 */
    val BAND_HEAD_HEIGHT: Dp = 24.dp
    /** 时段之间的留白：用间距分层，取代原来的分隔线 */
    val BAND_GAP: Dp = 12.dp

    /**
     * 网格这类密集数据面的字号上限：5 列下每列只有 63dp，
     * 12sp 的课名放到 2 倍就变成「一字一行」，竖排得读不出来。
     * 所以网格最多放到 1.5 倍，其余界面仍然完全跟随系统。
     */
    const val MAX_FONT_SCALE = 1.5f
    /** 时间轴只随字体放到 1.4 倍：再宽会把天列挤窄 */
    const val AXIS_FONT_SCALE_CAP = 1.4f
}

/**
 * 网格的纵向节奏。
 *
 * 课块里全是文字，而行高原本固定 46dp——系统字体调到 1.3 倍以上就会把课名裁掉半行。
 * 这里让容器随 fontScale 同比放大：比例不变，所以 1.0 倍下像素级不变；
 * 超过 [GridDims.MAX_FONT_SCALE] 后容器封顶，改由 [blockContentFor] 减少文字行数。
 */
data class GridMetrics(
    /** 真实生效的字体倍率（已按 [GridDims.MAX_FONT_SCALE] 封顶），用于 sp→dp 换算与行数收敛 */
    val fontScale: Float,
    val timeAxisWidth: Dp,
    val headerHeight: Dp,
    val sectionHeight: Dp,
    val bandHeadHeight: Dp,
    val bandGap: Dp,
) {
    /** 容器倍率：纵向尺寸相对 fontScale=1.0 基准的放大比例 */
    val boxScale: Float get() = sectionHeight.value / GridDims.SECTION_HEIGHT.value

    companion object {
        val Default: GridMetrics = of(1f)

        fun of(fontScale: Float): GridMetrics {
            val raw = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
            // 容器和文字用同一个封顶倍率，比例不变，1.0 倍下像素级不变
            val scale = raw.coerceAtMost(GridDims.MAX_FONT_SCALE)
            val axis = scale.coerceAtMost(GridDims.AXIS_FONT_SCALE_CAP)
            return GridMetrics(
                fontScale = scale,
                timeAxisWidth = GridDims.TIME_AXIS_WIDTH * axis,
                headerHeight = GridDims.HEADER_HEIGHT * scale,
                sectionHeight = GridDims.SECTION_HEIGHT * scale,
                bandHeadHeight = GridDims.BAND_HEAD_HEIGHT * scale,
                bandGap = GridDims.BAND_GAP * scale,
            )
        }
    }
}

/**
 * 网格区专用的 density：只压 fontScale，不动 density（dp 换算不受影响）。
 * 套在网格与表头外层，网格内所有 sp 就自动落在 1.5 倍上限内。
 */
@Composable
fun rememberGridDensity(): Density {
    val density = LocalDensity.current
    return remember(density) {
        if (density.fontScale > GridDims.MAX_FONT_SCALE) {
            Density(density.density, GridDims.MAX_FONT_SCALE)
        } else {
            density
        }
    }
}

/** 网格、课块、时间轴、当前时间线读同一个 metrics，避免各自算一套坐标而错位 */
@Composable
fun rememberGridMetrics(): GridMetrics {
    val fontScale = LocalDensity.current.fontScale
    return remember(fontScale) { GridMetrics.of(fontScale) }
}

/**
 * 一个时段（上午 / 下午 / 晚上）在纵向坐标里的占位。
 * [headTop] 是标题行的顶边，[bodyTop] 是该时段第一节小节的顶边。
 */
data class GridBand(
    val label: String,
    val startIndex: Int,
    val count: Int,
    val headTop: Dp,
    val bodyTop: Dp,
    val timeRange: String,
)

/** 当前时刻落在网格里的位置：[y] 为绝对纵坐标，[inClass] 表示是否正在某一小节内 */
data class GridSlot(val y: Dp, val inClass: Boolean)

/** 一个课块纵向放得下的内容：标题行数是硬的，其余按剩余空间依次砍 */
data class BlockContent(
    val titleLines: Int,
    val showRoom: Boolean,
    val showTeacher: Boolean,
    val showFooter: Boolean,
)

/**
 * 课块内容的降级顺序：标题 → 块底页脚（剩余时间 / 周次）→ 教室 → 教师。
 *
 * 1.0 倍下返回的就是原来的 2/3/4 行 + 教室 + 教师 + 页脚，视觉不变；
 * 字体放大后从下往上依次让位，保证任何倍率下都不裁字。
 */
fun blockContentFor(span: Int, metrics: GridMetrics): BlockContent {
    val safeSpan = span.coerceAtLeast(1)
    // 上下内边距 7dp + 块底留白 3dp + 2dp 安全余量
    val inner = metrics.sectionHeight.value * safeSpan - 12f
    val fs = metrics.fontScale
    val titleLine = 15f * fs
    val baseLines = when {
        safeSpan <= 1 -> 2
        safeSpan == 2 -> 3
        else -> 4
    }
    val titleLines = minOf(baseLines, (inner / titleLine).toInt().coerceAtLeast(1))
    var rest = inner - titleLines * titleLine

    val roomLine = 13f * fs // 10sp 行高 12 + 1dp 间距
    val teacherLine = 12f * fs
    val footerLine = 13f * fs // 9.5sp 行高 11 + 2dp 间距

    val showRoom = safeSpan >= 2 && rest >= roomLine + 1f
    if (showRoom) rest -= roomLine
    val showTeacher = safeSpan >= 3 && showRoom && rest >= teacherLine
    if (showTeacher) rest -= teacherLine
    val showFooter = safeSpan >= 2 && rest >= footerLine

    return BlockContent(titleLines, showRoom, showTeacher, showFooter)
}
