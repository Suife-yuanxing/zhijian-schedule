package com.zhijian.schedule.feature.schedule

import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate

/**
 * 选周面板的分行纯逻辑：每行 [columns] 个周格，行上方可带「N 月」分界小标。
 *
 * 分界规则：本行行首周的周一月份与上一行行首周的周一月份不同时，把新月份标在行首。
 * 4 列的行进节奏下标签逐行推进，等于给每一行一个月份锚点。
 *
 * 纯逻辑、无 Compose 依赖（同 [WeekAnchorPolicy] 的取舍），便于 JVM 单测。
 */
internal object WeekPickerRows {
    /** @param monthLabel 非 null 表示该行上方要画「N 月」分界 */
    data class Row(
        val weeks: List<Int>,
        val monthLabel: Int?,
    )

    fun build(
        totalWeeks: Int,
        startDate: LocalDate?,
        columns: Int = 4,
    ): List<Row> {
        if (totalWeeks <= 0) return emptyList()
        val rows = (1..totalWeeks).chunked(columns)
        return rows.mapIndexed { index, weeks ->
            val label =
                startDate?.let { sd ->
                    val month = TimeUtils.dateOfWeekDay(sd, weeks.first(), 1).monthValue
                    val previous =
                        (index - 1)
                            .takeIf { it >= 0 }
                            ?.let { TimeUtils.dateOfWeekDay(sd, rows[it].first(), 1).monthValue }
                    if (previous != null && month != previous) month else null
                }
            Row(weeks, label)
        }
    }
}
