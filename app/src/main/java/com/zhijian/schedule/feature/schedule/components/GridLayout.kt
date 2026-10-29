package com.zhijian.schedule.feature.schedule.components
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.util.TimeUtils
import java.time.Duration
import java.time.LocalTime

/**
 * 课表网格的纵向几何：把 12 小节切成 2~3 个时段，每个时段前插一行标题，段间留白。
 *
 * 旧实现是 `sectionCount * SECTION_HEIGHT` 的等距排布 + 一整套分隔线；
 * 现在所有间距都来自几何本身，绘制层只需要知道「第 i 节的顶边在哪」。
 */
class GridLayout(
    val sections: List<SectionTimeEntity>,
    val metrics: GridMetrics = GridMetrics.Default,
) {
    val bands: List<GridBand>
    val totalHeight: Dp

    init {
        val groups = mutableListOf<MutableList<SectionTimeEntity>>()
        sections.forEach { section ->
            val label = bandLabelOf(section)
            val last = groups.lastOrNull()
            if (last != null && bandLabelOf(last.first()) == label) {
                last.add(section)
            } else {
                groups.add(mutableListOf(section))
            }
        }

        val built = mutableListOf<GridBand>()
        var cursor = 0.dp
        var index = 0
        groups.forEach { group ->
            val band =
                GridBand(
                    label = bandLabelOf(group.first()),
                    startIndex = index,
                    count = group.size,
                    headTop = cursor,
                    bodyTop = cursor + metrics.bandHeadHeight,
                    timeRange = "${group.first().startTime} – ${group.last().endTime}",
                )
            built.add(band)
            index += group.size
            cursor = band.bodyTop + metrics.sectionHeight * group.size + metrics.bandGap
        }
        bands = built
        totalHeight = if (built.isEmpty()) 0.dp else cursor - metrics.bandGap
    }

    /** 第 index（0 基）节的顶边绝对坐标 */
    fun sectionTop(index: Int): Dp {
        val clamped = index.coerceIn(0, (sections.size - 1).coerceAtLeast(0))
        val band = bands.lastOrNull { clamped >= it.startIndex } ?: return 0.dp
        return band.bodyTop + metrics.sectionHeight * (clamped - band.startIndex)
    }

    /** 绝对纵坐标 → 第几节（0 基），用于空白处点击新建 */
    fun sectionAt(y: Dp): Int {
        bands.forEach { band ->
            val height = metrics.sectionHeight * band.count
            if (y < band.bodyTop + height) {
                val raw = ((y - band.bodyTop) / metrics.sectionHeight).toInt()
                return band.startIndex + raw.coerceIn(0, band.count - 1)
            }
        }
        return (sections.size - 1).coerceAtLeast(0)
    }

    /**
     * 当前时刻的位置。
     * 课时内按真实分钟线性插值；课间停在下一节的上边界（旧实现在这里直接返回 null，红线整段消失）。
     */
    fun timeSlot(now: LocalTime): GridSlot? {
        sections.forEachIndexed { i, section ->
            val start = TimeUtils.parseTime(section.startTime) ?: return@forEachIndexed
            val end = TimeUtils.parseTime(section.endTime) ?: return@forEachIndexed
            val span = Duration.between(start, end).seconds
            if (span > 0 && !now.isBefore(start) && !now.isAfter(end)) {
                val fraction = (Duration.between(start, now).seconds.toFloat() / span).coerceIn(0f, 1f)
                return GridSlot(sectionTop(i) + metrics.sectionHeight * fraction, inClass = true)
            }
        }
        sections.forEachIndexed { i, section ->
            val start = TimeUtils.parseTime(section.startTime) ?: return@forEachIndexed
            if (now.isBefore(start)) {
                val prevEnd = sections.getOrNull(i - 1)?.let { TimeUtils.parseTime(it.endTime) }
                return if (prevEnd != null && now.isAfter(prevEnd)) {
                    GridSlot(sectionTop(i), inClass = false)
                } else {
                    null
                }
            }
        }
        return null
    }

    /** 时段内的大节分界（以「第几节」计），用于画段内 hairline */
    fun periodBoundaries(band: GridBand): List<Int> {
        val slice = sections.subList(band.startIndex, (band.startIndex + band.count).coerceAtMost(sections.size))
        return (1 until slice.size).filter { slice[it].periodNo != slice[it - 1].periodNo }
    }
}

/** 按开课时间判断该节属于上午 / 下午 / 晚上（数据里的 session 只有 AM/PM，靠时间更可靠） */
private fun bandLabelOf(section: SectionTimeEntity): String {
    val hour = section.startTime.substringBefore(':').toIntOrNull()
    return when {
        hour == null -> if (section.session == "AM") "上午" else "下午"
        hour < 12 -> "上午"
        hour < 18 -> "下午"
        else -> "晚上"
    }
}
