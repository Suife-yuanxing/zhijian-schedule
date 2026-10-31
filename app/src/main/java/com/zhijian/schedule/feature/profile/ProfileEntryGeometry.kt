package com.zhijian.schedule.feature.profile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.zhijian.schedule.core.util.lerpFloat
import com.zhijian.schedule.core.util.lerpRect
import com.zhijian.schedule.core.util.smoothstep
import kotlin.math.max
import kotlin.math.min

/*
 * 入口卡转场的几何与关键帧。
 *
 * 展开（进入二级页）与回收（返回）的每一个像素都由这里推出：容器矩形、cover 缩放、
 * 平移、裁剪框、圆角与各段透明度。全部是纯函数——不读组合状态、不触发重组、不碰绘制 API，
 * 所以能脱离 Compose 直接在单测里钉住（见 ProfileEntryGeometryTest）。
 *
 * 抽出来的理由：调关键帧时原先是靠真机肉眼看「卡片展开得像不像纸面」，改一个系数要重编、
 * 装机、逐帧看；现在是改完跑一条测试就知道端点与单调性有没有被破坏。
 */

/** 收拢进度：起手快、尾声长，纸面先迅速贴近卡片轮廓，再软着陆 */
internal fun collapseProgress(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}

/**
 * 收回时最后一段的淡出：内容先融进卡片，再由 ProfileEntryPage 撤掉页面。
 * 区间收得很窄，只在容器已接近卡片大小时发生，避免大块页面半透明透出底页。
 */
internal fun cardHandOffAlpha(
    progress: Float,
    fadeToCard: Boolean,
): Float = if (fadeToCard) smoothstep(0f, 0.12f, progress) else 1f

/** 进入展开：从入口卡矩形插值到整页内容矩形的容器 */
internal fun entryForwardContainer(
    cardRect: Rect,
    contentSize: Size,
    progress: Float,
): Rect = lerpRect(cardRect, Rect(Offset.Zero, contentSize), progress)

/** 展开容器的圆角：随进度收到 0，且不超过短边的一半 */
internal fun entryForwardRadius(
    cardRect: Rect,
    contentSize: Size,
    progress: Float,
    cardRadius: Float,
): Float {
    val container = entryForwardContainer(cardRect, contentSize, progress)
    return lerpFloat(cardRadius, 0f, progress)
        .coerceIn(0f, min(container.width, container.height) / 2f)
}

/**
 * 进入展开的图层变换：图层先在整页坐标里按 cover 缩放对齐裁剪框，再把裁剪框反算回内容坐标，
 * 这样同一个 graphicsLayer 就能同时完成「窗口展开」和「内容等比缩放」。
 */
internal data class EntryCoverTransform(
    /** 裁剪框，内容坐标系 */
    val clipRect: Rect,
    /** cover 缩放倍率 */
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    /** 内容坐标系下的圆角 */
    val radius: Float,
)

internal fun entryCoverTransform(
    cardRect: Rect,
    contentSize: Size,
    progress: Float,
    cardRadius: Float,
): EntryCoverTransform {
    val container = entryForwardContainer(cardRect, contentSize, progress)
    val cover =
        max(
            container.width / contentSize.width,
            container.height / contentSize.height,
        ).coerceAtLeast(0.01f)
    val tx = container.center.x - contentSize.width * cover / 2f
    val ty = container.center.y - contentSize.height * cover / 2f
    val localClip =
        Rect(
            left = (container.left - tx) / cover,
            top = (container.top - ty) / cover,
            right = (container.right - tx) / cover,
            bottom = (container.bottom - ty) / cover,
        )
    val radius =
        (lerpFloat(cardRadius, 0f, progress) / cover)
            .coerceIn(0f, min(localClip.width, localClip.height) / 2f)
    return EntryCoverTransform(
        clipRect = localClip,
        scale = cover,
        translationX = tx,
        translationY = ty,
        radius = radius,
    )
}

/** 回收浮层在某一进度上的全部几何与透明度 */
internal data class EntryCollapseFrame(
    val container: Rect,
    val radius: Float,
    val contentAlpha: Float,
    val fillAlpha: Float,
    val borderAlpha: Float,
    /** 纸面底色混色比：0 = 页面底色，1 = 卡片面色 */
    val fillMix: Float,
    /** cover 缩放倍率 */
    val coverScale: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * 返回回收：整页快照沿一条曲线直接收进点按的入口卡。
 * 内容前段淡出，纸面颜色逐渐过渡到卡片色，最后透出下方真实的入口卡。
 */
internal fun entryCollapseFrame(
    full: Rect,
    target: Rect,
    progress: Float,
    cardRadius: Float,
): EntryCollapseFrame {
    val move = collapseProgress(progress)
    val container = lerpRect(full, target, move)
    val radius =
        (cardRadius * smoothstep(0f, 0.3f, move))
            .coerceAtMost(min(container.width, container.height) / 2f)
    val cover =
        max(container.width / full.width, container.height / full.height)
            .coerceAtLeast(0.01f)
    val dstW = full.width * cover
    val dstH = full.height * cover
    return EntryCollapseFrame(
        container = container,
        radius = radius,
        contentAlpha = 1f - smoothstep(0.16f, 0.52f, progress),
        fillAlpha = 1f - smoothstep(0.72f, 0.98f, progress),
        borderAlpha =
            smoothstep(0.02f, 0.12f, progress) *
                (1f - smoothstep(0.55f, 0.85f, progress)),
        fillMix = smoothstep(0.15f, 0.6f, move),
        coverScale = cover,
        translationX = container.center.x - dstW / 2f,
        translationY = container.center.y - dstH / 2f,
    )
}

/**
 * 展开时容器内侧的玻璃流光：一道对角光带随进度扫过容器。
 * 形状只给「光带在线上的位置」与透明度，画法（渐变端点、colorStops）由绘制侧实现。
 */
internal data class EntrySheenFrame(
    /** 光带中心在渐变线上的位置比例，0 → 1 随展开从卡片一角行进到对角 */
    val bandCenter: Float,
    /** 光带半宽（渐变线比例） */
    val bandHalfWidth: Float,
    val alpha: Float,
)

/**
 * 内侧流光的关键帧：中段见峰、两端完全熄灭——玻璃被「擦亮」一下，落位即干净。
 * bandCenter 全程单调前移，光带不会倒卷。
 */
internal fun entrySheenFrame(progress: Float): EntrySheenFrame {
    val p = progress.coerceIn(0f, 1f)
    return EntrySheenFrame(
        bandCenter = 0.12f + 0.88f * p,
        bandHalfWidth = 0.16f,
        alpha =
            ENTRY_SHEEN_MAX_ALPHA *
                smoothstep(0.06f, 0.4f, p) *
                (1f - smoothstep(0.62f, 0.96f, p)),
    )
}

/**
 * 展开容器边缘的周向扫光：一道高光段沿 sweepGradient 行进，叠在既有 border 描边上。
 */
internal data class EntryEdgeSweepFrame(
    /** 高光段中心在周向上的位置比例（0 = sweepGradient 起点，随展开行进一整圈的一半） */
    val centerStop: Float,
    /** 高光段的半宽（周向比例） */
    val halfWidth: Float,
    val alpha: Float,
)

/** 边缘扫光的关键帧：与内侧流光同一节奏（中段见峰、端点熄灭），位置全程前进 */
internal fun entryEdgeSweepFrame(progress: Float): EntryEdgeSweepFrame {
    val p = progress.coerceIn(0f, 1f)
    return EntryEdgeSweepFrame(
        centerStop = p,
        halfWidth = 0.09f,
        alpha =
            ENTRY_SWEEP_MAX_ALPHA *
                smoothstep(0.05f, 0.32f, p) *
                (1f - smoothstep(0.55f, 0.9f, p)),
    )
}

/** 录制层在画布坐标里的整块矩形；图层尚未出图（宽高为 0）时退回画布尺寸 */
internal fun entryCaptureBounds(
    layerWidth: Int,
    layerHeight: Int,
    canvasSize: Size,
): Rect =
    Rect(
        left = 0f,
        top = 0f,
        right = layerWidth.toFloat().takeIf { it > 0f } ?: canvasSize.width,
        bottom = layerHeight.toFloat().takeIf { it > 0f } ?: canvasSize.height,
    )

/** 入口卡不在屏上时的兜底落点：画布下部居中的一条窄带 */
internal fun entryExitFallbackTarget(canvasSize: Size): Rect =
    Rect(
        left = canvasSize.width * 0.1f,
        top = canvasSize.height * 0.58f,
        right = canvasSize.width * 0.9f,
        bottom = canvasSize.height * 0.64f,
    )
