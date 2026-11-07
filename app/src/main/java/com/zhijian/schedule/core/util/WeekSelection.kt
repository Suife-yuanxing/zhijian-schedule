package com.zhijian.schedule.core.util

/**
 * 周次集合的「结构识别」：从 [weeks] 反推它属于哪种选择形态。
 *
 * 为什么要有这一层：课程编辑器的周次选择有三种快捷形态（连续 / 单周 / 双周），
 * 但真值只有一个 `Set<Int>`。如果把形态也存成状态，就会出现「集合改了、形态没跟上」
 * 的双写失配——旧实现里 全选 / 单周 / 双周 / 清空 四处各自写集合，正是这种结构。
 * 这里把形态一律由集合推导，不落状态，改动只剩一个入口。
 *
 * 纯逻辑：不依赖 Compose 与 android.*，可直接被 JVM 单测覆盖。
 */
object WeekSelection {
    /** 选择形态；CUSTOM 表示无法用区间或单双周表达的稀疏组合 */
    enum class Mode {
        /** 尚未选择任何周 */
        EMPTY,

        /** 连续区间：滑块可直接表达 */
        RANGE,

        /** 区间内的纯单周 */
        ODD,

        /** 区间内的纯双周 */
        EVEN,

        /** 其余稀疏组合：只能逐周挑 */
        CUSTOM,
    }

    fun mode(weeks: Set<Int>): Mode {
        val sorted = weeks.sorted()
        val steps = sorted.zipWithNext().map { (a, b) -> b - a }.toSet()
        return when {
            sorted.isEmpty() -> Mode.EMPTY
            // 单周也能被区间表达（起点 = 终点），所以只有一个元素时直接算 RANGE
            sorted.size == 1 -> Mode.RANGE
            steps == setOf(1) -> Mode.RANGE
            steps == setOf(2) -> if (sorted.first() % 2 == 1) Mode.ODD else Mode.EVEN
            // 间距忽大忽小、或等差但不是 1/2：只能逐周挑
            else -> Mode.CUSTOM
        }
    }

    /** 选中集合的闭包区间；空集返回 null（调用方各自给默认区间） */
    fun bounds(weeks: Set<Int>): IntRange? {
        if (weeks.isEmpty()) return null
        return weeks.min()..weeks.max()
    }

    /**
     * 连续区间 → 周次集合。端点可反序（滑块拖动时会短暂交叉），
     * 越界部分裁到 1..[totalWeeks]。
     */
    fun range(
        start: Int,
        end: Int,
        totalWeeks: Int,
    ): Set<Int> {
        val lo = maxOf(1, minOf(start, end))
        val hi = minOf(totalWeeks, maxOf(start, end))
        if (lo > hi) return emptySet()
        return (lo..hi).toSet()
    }

    /**
     * 在 [bounds] 内取单周或双周。空区间时由调用方先给一个默认区间。
     * 越界端点同样裁到 1..[totalWeeks]，避免学期周数调小后残留越界周次。
     */
    fun parity(
        bounds: IntRange,
        odd: Boolean,
        totalWeeks: Int,
    ): Set<Int> {
        val lo = maxOf(1, bounds.first)
        val hi = minOf(totalWeeks, bounds.last)
        if (lo > hi) return emptySet()
        return (lo..hi).filter { (it % 2 == 1) == odd }.toSet()
    }
}
