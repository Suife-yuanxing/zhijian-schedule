package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.jw.JwMeta

/**
 * 「课表信息跟着抓取走」的落库决策：教务声明了什么就覆盖什么，没声明的字段
 * 原样保留——字段各自独立，周历失败不该连学期名一起丢。
 *
 * 总周数与 [com.zhijian.schedule.feature.settings.SettingsViewModel.saveTimetable]
 * 的手填上限（1..30）同口径，异常数据写不进库。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object TimetableMetaPlan {
    /** 教务元数据 → 课表实体；没有任何字段会变时返回原实例 */
    fun apply(
        entity: TimetableEntity,
        meta: JwMeta,
    ): TimetableEntity {
        val updated =
            entity.copy(
                semester = meta.semester.ifBlank { entity.semester },
                startDate = meta.startDate.ifBlank { entity.startDate },
                totalWeeks =
                    if (meta.totalWeeks > 0) {
                        meta.totalWeeks.coerceIn(1, MAX_WEEKS)
                    } else {
                        entity.totalWeeks
                    },
            )
        return if (updated == entity) entity else updated
    }

    /** 与手填口径一致的总周数上限 */
    const val MAX_WEEKS = 30
}
