package com.zhijian.schedule.feature.schedule.components
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.GridStyles
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.feature.schedule.ScheduleUiState

/** 时段标题：编辑式是「上午 ——— 08:20 – 11:45」，色块制是一条浅底纹条 */
@Composable
internal fun BandHeader(
    band: GridBand,
    style: String,
    metrics: GridMetrics,
) {
    val colors = LocalCourseColors.current
    val guide = lerp(colors.border, colors.text, 0.18f)
    if (style == GridStyles.BLOCK) {
        // 底纹条要「看得见但不抢戏」：surfaceVariant 对纸本底色的对比只有 1.06:1，往 ink 掺 6%
        // 正好落到 1.2:1 左右，和编辑式的 hairline 同一量级
        val strip = lerp(colors.surfaceVariant, colors.text, 0.035f)
        Row(
            Modifier
                .fillMaxWidth()
                .height(metrics.bandHeadHeight)
                .background(
                    strip,
                    RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp),
                ).padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = band.label,
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(text = band.timeRange, color = microText(colors), fontSize = 9.sp)
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .height(metrics.bandHeadHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = band.label,
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(guide.copy(alpha = 0.6f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = band.timeRange, color = microText(colors), fontSize = 9.sp)
        }
    }
}

/**
 * 一个时段在屏幕上的一切「不变事实」：它属于哪一段、怎么画、跨哪几列。
 *
 * 打成一个包是因为 [BandBody] 原本要收 8 个参数才凑齐这些——而它们全都是同一次
 * 布局计算的产物，来自 [WeekGrid] 里同一份 `layout` / `metrics` / `visibleDays`。
 * 拆开传只会让每个新需求（例如这次的扩散原点）都再往签名上添一项。
 */
internal class BandScene(
    val band: GridBand,
    val layout: GridLayout,
    val ui: ScheduleUiState,
    val week: Int,
    val visibleDays: List<Int>,
    val style: String,
    val metrics: GridMetrics,
)

/**
 * 时段左侧的时间轴：大节序号 + 起止时间。
 *
 * 时段标题只给整段的起止，单节结束时间必须在这里补齐，所以它是按 periodNo 分组的，
 * 不是逐节平铺。抽出来的理由很实在：它和「可点击的天列」是两件毫不相干的事，
 * 挤在 [BandBody] 里时那个函数会刚好越过 detekt 的 100 行线。
 */
@Composable
private fun BandTimeAxis(
    sections: List<SectionTimeEntity>,
    metrics: GridMetrics,
) {
    val colors = LocalCourseColors.current
    Column(Modifier.width(metrics.timeAxisWidth).fillMaxHeight()) {
        sections.groupBy { it.periodNo }.forEach { (periodNo, secs) ->
            val start = secs.firstOrNull()?.startTime.orEmpty()
            val end = secs.lastOrNull()?.endTime.orEmpty()
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(metrics.sectionHeight * secs.size),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "$periodNo",
                    color = colors.text,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TabularFigure,
                )
                Text(
                    text = if (end.isNotEmpty()) "$start–$end" else start,
                    color = microText(colors),
                    fontSize = 9.sp,
                    style = TabularFigure,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** 一个时段的网格体：时间轴 + 天列底纹（课块不在这里，见 CourseOverlay） */
@Composable
internal fun BandBody(
    scene: BandScene,
    onEmptyTap: (day: Int, section: Int, seed: SeedShape) -> Unit,
) {
    val band = scene.band
    val layout = scene.layout
    val ui = scene.ui
    val week = scene.week
    val visibleDays = scene.visibleDays
    val style = scene.style
    val metrics = scene.metrics
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val guide = lerp(colors.border, colors.text, 0.18f)
    val sectionPx = with(density) { metrics.sectionHeight.toPx() }
    // 空课块种子的角半径：与真实课块同一个 token，扩散起手那几帧的四角才对得上
    val blockDp = LocalCoursePreset.current.radius.blockDp
    val blockCornerPx = with(density) { blockDp.dp.toPx() }
    val bandSections =
        ui.sections.subList(
            band.startIndex,
            (band.startIndex + band.count).coerceAtMost(ui.sections.size),
        )
    val boundaries = remember(band.startIndex, band.count) { layout.periodBoundaries(band) }

    Row(Modifier.fillMaxWidth().height(metrics.sectionHeight * band.count)) {
        BandTimeAxis(bandSections, metrics)

        visibleDays.forEach { day ->
            // 这一整列在根坐标系里的位置：点空课块时的圆形扩散要从「被点的那一格」长出来，
            // 而点击回调拿到的只是列内偏移。列框存在这里，offset 再由 EmptyCellGeometry 折成格子。
            var columnBounds by remember { mutableStateOf(Rect.Zero) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { columnBounds = it.boundsInRoot() }
                    .drawBehind {
                        // 列分隔：旧实现用的是 1.23:1 的 border，等于没画
                        drawLine(
                            guide.copy(alpha = 0.35f),
                            androidx.compose.ui.geometry
                                .Offset(size.width - 0.5f, 0f),
                            androidx.compose.ui.geometry
                                .Offset(size.width - 0.5f, size.height),
                            strokeWidth = 1f,
                        )
                        // 段内「大节分界」hairline；色块制不画线，用底纹条分层
                        if (style != GridStyles.BLOCK) {
                            boundaries.forEach { index ->
                                val y = index * sectionPx
                                drawLine(
                                    guide.copy(alpha = 0.55f),
                                    androidx.compose.ui.geometry
                                        .Offset(0f, y),
                                    androidx.compose.ui.geometry
                                        .Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                    }.pointerInput(week, day, band.startIndex, band.count) {
                        detectTapGestures { offset ->
                            val local = (offset.y / sectionPx).toInt().coerceIn(0, band.count - 1)
                            // 交点算成格子矩形：扩散圆心落在这里，圆外仍是旧课表。
                            // 退化（列框还没测出来）时给 Rect.Zero，调用方据此跳过扩散直接导航。
                            val cell =
                                EmptyCellGeometry.cellOrColumn(
                                    column = columnBounds,
                                    sectionHeightPx = sectionPx,
                                    localIndex = local,
                                    sectionCount = band.count,
                                )
                            // 空课块的种子：整格轮廓 + 课块自己的圆角。格子是被点的那一格本身，
                            // 所以扩散第 0 帧的边界正好是这一格的边——页面从格子里长出来。
                            onEmptyTap(
                                day,
                                band.startIndex + local + 1,
                                SeedShape(
                                    bounds = cell,
                                    cornerRadius = blockCornerPx,
                                ),
                            )
                        }
                    },
            )
        }
    }
}
