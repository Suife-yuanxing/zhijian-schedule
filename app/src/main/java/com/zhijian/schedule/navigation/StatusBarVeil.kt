package com.zhijian.schedule.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors

/**
 * 状态栏轻纱：真全面屏下内容直接铺到状态栏底下、被裁掉的部分完整可见，
 * 这里只叠一层几乎不可见的 bg 渐变（顶部 0.32 → 0），给时钟与状态图标
 * 留一点底色对比，不遮挡任何内容。
 *
 * 为什么不做磨砂/重遮罩：顶部的设计意图就是「让被裁剪的内容显示出来」，
 * 内容由各根页自己画到状态栏底下，这里不再重复绘制。
 */
@Composable
internal fun StatusBarVeil(
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(statusBarHeight + STATUS_VEIL_FEATHER)
            .drawWithContent {
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to colors.bg.copy(alpha = 0.32f),
                                    0.55f to colors.bg.copy(alpha = 0.14f),
                                    1f to androidx.compose.ui.graphics.Color.Transparent,
                                ),
                        ),
                )
            },
    )
}

/** 羽化带：轻纱在状态栏下沿再拖一小段，接缝不可见 */
internal val STATUS_VEIL_FEATHER = 10.dp
