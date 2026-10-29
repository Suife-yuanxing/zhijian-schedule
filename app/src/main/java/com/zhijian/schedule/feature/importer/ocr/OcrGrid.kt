package com.zhijian.schedule.feature.importer.ocr

import kotlin.math.abs

/**
 * OCR 网格几何：表头列布局、节次锚点行映射，以及 OCR 漏读表头时的列插值。
 */
internal object OcrGrid {
    /** 列布局：由表头中心距推出列宽与左右边界，并把一行文本归属到某一列；列外返回 null。 */
    class ColumnLayout(
        private val centers: List<Float>,
        private val colGap: Float,
    ) {
        val leftEdge: Float = centers.first() - colGap / 2f
        val rightEdge: Float = centers.last() + colGap / 2f

        fun columnOf(line: OcrLine): Int? {
            if (line.centerX < leftEdge || line.centerX > rightEdge) return null
            var best = -1
            var bestOverlap = 0f
            centers.forEachIndexed { i, c ->
                val overlap = minOf(line.right, c + colGap / 2f) - maxOf(line.left, c - colGap / 2f)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = i
                }
            }
            return if (best >= 0) best else null
        }
    }

    /** OCR 漏读某天表头（如“星期二”→"星期"）时，按列间距与日期序号插值补出虚拟表头 */
    fun expandDayHeaders(headers: Map<Int, OcrLine>): Map<Int, OcrLine> {
        val days = headers.keys.sorted()
        if (days.size < 2) return headers
        val centers = days.map { headers.getValue(it).centerX }
        val colGap = median(centers.zipWithNext { a, b -> b - a })
        if (colGap <= 0f) return headers
        val full = LinkedHashMap<Int, OcrLine>()
        days.forEachIndexed { i, day ->
            val line = headers.getValue(day)
            full[day] = line
            if (i == days.size - 1) return@forEachIndexed
            val nextDay = days[i + 1]
            val numGap = nextDay - day
            if (numGap < 2) return@forEachIndexed
            val nextLine = headers.getValue(nextDay)
            val pxGap = nextLine.centerX - line.centerX
            if (abs(pxGap / colGap - numGap) > 0.55f) return@forEachIndexed
            for (k in 1 until numGap) {
                val cx = line.centerX + pxGap * k / numGap
                full[day + k] = OcrLine("", cx - 0.5f, line.top, cx + 0.5f, line.bottom)
            }
        }
        return full
    }

    data class Anchor(
        val n: Int,
        val big: Boolean,
        val y: Float,
    )

    class RowMap(
        private val ranges: List<IntRange>,
        private val edges: List<Float>,
    ) {
        val medianRowHeight: Float
            get() = if (edges.size >= 2) median(edges.zipWithNext { a, b -> b - a }) else 100f

        fun rangeAt(y: Float): IntRange {
            if (ranges.isEmpty()) return 1..1
            var idx = 0
            for (k in edges.indices) {
                if (y >= edges[k]) idx = k else break
            }
            return ranges[idx.coerceIn(0, ranges.size - 1)]
        }

        companion object {
            fun build(
                anchors: List<Anchor>,
                gridTop: Float,
                gridBottom: Float,
                totalSections: Int,
            ): RowMap {
                if (anchors.size >= 2) {
                    val big = anchors.any { it.big } || anchors.maxOf { it.n } * 2 <= totalSections + 1
                    val ranges =
                        anchors.map { a ->
                            if (big) {
                                (a.n * 2 - 1)..minOf(a.n * 2, totalSections)
                            } else {
                                a.n.coerceIn(1, totalSections).let { it..it }
                            }
                        }
                    val edges = mutableListOf<Float>()
                    edges += gridTop
                    for (k in 1 until anchors.size) edges += (anchors[k - 1].y + anchors[k].y) / 2f
                    edges += gridBottom
                    return RowMap(ranges, edges)
                }
                val rows = (totalSections + 1) / 2
                val span = (gridBottom - gridTop).coerceAtLeast(1f)
                val ranges = (0 until rows).map { i -> (i * 2 + 1)..minOf(i * 2 + 2, totalSections) }
                val edges = (0..rows).map { i -> gridTop + span * i / rows }
                return RowMap(ranges, edges)
            }
        }
    }

    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
