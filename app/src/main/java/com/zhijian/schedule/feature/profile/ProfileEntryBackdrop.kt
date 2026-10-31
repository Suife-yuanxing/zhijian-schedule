package com.zhijian.schedule.feature.profile

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.rememberMotionAllowed

/**
 * 「我的」页背景的景深磨砂：入口卡展开期间把页面内容 blur + 向 bg 色冲淡一层，
 * 让浮起的卡片读作「离开纸面」；返回收回时随 [ProfileEntryTransitions.backdrop]
 * 释放，纸面从 frosted 一路回清晰。
 *
 * 为什么不用 `Modifier.blur`：它的 radius 是组合期参数，逐帧变化会整页重组。
 * 这里把 [ProfileEntryTransitions.backdrop] 的读取全部放在图层/绘制阶段
 * （graphicsLayer lambda 与 drawWithContent），转场全程零重组。
 *
 * 降级路径：RenderEffect 仅 API 31+ 生效，低版本只剩压暗 scrim——层级感保留，
 * 磨砂放弃；「移除动画」（ANIMATOR_DURATION_SCALE = 0）时整层跳过，纯装饰不留痕。
 */
@Composable
internal fun Modifier.entryBackdrop(transitions: ProfileEntryTransitions): Modifier {
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val motionAllowed = rememberMotionAllowed()
    val maxBlurPx =
        if (motionAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            with(density) { ENTRY_BACKDROP_BLUR_DP.dp.toPx() }
        } else {
            0f
        }
    return this
        .graphicsLayer {
            val b = transitions.backdrop
            renderEffect =
                if (maxBlurPx > 0f && b > 0.01f) {
                    // Clamp：整屏底图，边缘钳位采样不出黑边（Modifier.blur 的 Rectangle 同款）
                    BlurEffect(maxBlurPx * b, maxBlurPx * b, TileMode.Clamp)
                } else {
                    null
                }
        }.drawWithContent {
            drawContent()
            val b = if (motionAllowed) transitions.backdrop else 0f
            if (b > 0.01f) {
                drawRect(colors.bg.copy(alpha = ENTRY_BACKDROP_SCRIM_ALPHA * b))
            }
        }
}
