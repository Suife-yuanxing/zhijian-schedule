package com.zhijian.schedule.feature.settings

import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 设置弹窗「学期时间轴」的纯逻辑：开学日 + 总周数 → 每周三态、结束日期与学期进度。
 * 「本周」用自然周序列（floorDiv），学期外不算错——第 0 周即未开学、超总周数即已结课。
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
internal object SemesterTimelinePlan {
    enum class Status { ACTIVE, NOT_STARTED, FINISHED }

    enum class WeekState { PAST, NOW, FUTURE }

    data class Week(
        val no: Int,
        val state: WeekState,
    )

    data class Model(
        val weeks: List<Week>,
        val currentWeek: Int?,
        val endDate: LocalDate?,
        val status: Status,
    )

    fun build(
        startDate: LocalDate?,
        totalWeeks: Int,
        today: LocalDate,
    ): Model {
        val empty = Model(emptyList(), null, null, Status.NOT_STARTED)
        val sd = startDate ?: return empty
        if (totalWeeks <= 0) return empty
        // 自然周序号：开学前为 0/负数，结课后可超总周数
        val rawWeek = Math.floorDiv(ChronoUnit.DAYS.between(sd, today), 7).toInt() + 1
        val status =
            when {
                rawWeek < 1 -> Status.NOT_STARTED
                rawWeek > totalWeeks -> Status.FINISHED
                else -> Status.ACTIVE
            }
        val weeks =
            (1..totalWeeks).map { no ->
                Week(no, when {
                    no < rawWeek -> WeekState.PAST
                    no == rawWeek -> WeekState.NOW
                    else -> WeekState.FUTURE
                })
            }
        return Model(weeks, rawWeek.takeIf { it in 1..totalWeeks }, TimeUtils.dateOfWeekDay(sd, totalWeeks, 7), status)
    }
}
