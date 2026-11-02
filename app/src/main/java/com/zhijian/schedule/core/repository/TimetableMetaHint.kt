package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta

/**
 * 一次导入会改哪些课表信息、哪一项该提醒用户自己核对。
 *
 * ## 为什么单独成对象
 * 原来这些判断长在 `TimetableImportService.metaHint` 里，与数据库读取缠在一起，
 * 进不了单测。而它判断的是「哪些字段会变」——判错的代价是用户看不到将要发生的改动，
 * 或者被一堆没变的字段淹没。抽成纯函数即可逐格枚举。
 *
 * ## 两个独立的问题
 * 1. **会变什么**：来源声明了、且与本地不同，才列出来（[changedLabels]）。
 * 2. **还缺什么**：来源只声明了一部分（比如只有学期名、没有开学日期）时，
 *    没声明的那部分会**安静地保留旧值**——旧值若来自建表当天，周次映射就是错的，
 *    而这正是「学期第 10 周显示成第 1 周」的成因。所以缺项要单独提醒（[missingHint]）。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object TimetableMetaHint {
    /** 缺开学日期时的提醒；比「去高级设置看看」具体，直接说清后果 */
    private const val MISSING_START_DATE = "开学日期未同步，学期周次可能不准，建议在高级设置里核对"

    /**
     * 这次导入真正会改的字段说明（只列会变的）。
     *
     * @param before 本地现值
     * @param after 同步后的值（已由 [TimetableMetaPlan.apply] 算好）
     * @return 形如 `["学期「…」", "第 1 周 2026-08-31"]`；一项都不变时为空
     */
    fun changedLabels(
        before: LocalMeta,
        after: LocalMeta,
    ): List<String> {
        val parts = mutableListOf<String>()
        if (after.semester != before.semester) parts += "学期「" + after.semester + "」"
        if (after.startDate != before.startDate) parts += "第 1 周 " + after.startDate
        if (after.totalWeeks != before.totalWeeks) parts += "总周数 " + after.totalWeeks
        return parts
    }

    /**
     * 来源没声明、因而会保留旧值的关键字段提醒；[meta] 为 null 时返回 null。
     *
     * 只挑**开学日期**：学期名错了用户一眼能看出来（顶栏就写着），总周数错了
     * 顶多显示范围略长略短，而开学日期错了会让整个学期的周次整体平移，
     * 且界面上没有任何一处会显得「不对劲」——这正是它需要被单独点名的原因。
     */
    fun missingHint(meta: JwMeta?): String? = if (meta != null && meta.startDate.isBlank()) MISSING_START_DATE else null

    /** 课表信息三元组，只为让 [changedLabels] 有个不依赖实体类型的入参形态 */
    data class LocalMeta(
        val semester: String,
        val startDate: String,
        val totalWeeks: Int,
    )
}
