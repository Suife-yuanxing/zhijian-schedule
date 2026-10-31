package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.CourseEntity

/**
 * 「取消」的口径：哪些本地课程会在导入预览里被标记为删除。
 *
 * 两条路语义不同：
 * - **教务自动同步**（[syncMode]）：没有确认环节，取消必须保守——首次同步一条不删
 *   （先建基线），之后也只删非手动编辑的旧课，再由 [ImportGuard] 兜底；
 * - **手动导入**（教务抓取 / CSV / 截图 / 口令 / 脚本…）：整表替换——没被新数据认领的
 *   本地课全部取消，**含手动编辑过的**。用户在预览里逐条看到课名后才确认，
 *   「导入 = 换课表」比「导入 = 往上摞」更符合直觉，否则旧课永远清不掉。
 */
internal object ImportReplace {
    /** 预览里要标记「取消」的本地课程；matchedIds 是已被新数据认领（新增/变更/无变化）的部分 */
    fun removalCandidates(
        existing: List<CourseEntity>,
        matchedIds: Set<Long>,
        syncMode: Boolean,
        firstSync: Boolean,
    ): List<CourseEntity> =
        when {
            syncMode ->
                if (firstSync) {
                    emptyList()
                } else {
                    existing.filter { it.id !in matchedIds && !it.manuallyEdited }
                }
            else -> existing.filter { it.id !in matchedIds }
        }
}
