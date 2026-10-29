package com.zhijian.schedule.core.repository

/**
 * 自动同步护栏：解析抖动/学校改版时，宁可暂停一次，也不要把整张课表改坏。
 *
 * 规则（只作用于教务自动同步，手动导入不拦）：
 * - 首次同步只增不删，先建立基线；
 * - 取消超过 max(3, 现有 25%) → 暂停；
 * - 新增超过 max(5, 现有 40%) → 暂停。
 *
 * 被拦下的预览仍会保留，用户可在导入页手动确认后应用。
 */
object ImportGuard {
    fun evaluate(
        existingCount: Int,
        addedCount: Int,
        removedCount: Int,
        firstSync: Boolean,
    ): String? {
        if (firstSync) return null
        if (existingCount < 5) return null

        val removeLimit = maxOf(3, existingCount * 25 / 100)
        if (removedCount > removeLimit) {
            return "本次要取消 $removedCount 门课（超过现有 $existingCount 门的 25%），已暂停自动应用"
        }
        val addLimit = maxOf(5, existingCount * 40 / 100)
        if (addedCount > addLimit) {
            return "本次要新增 $addedCount 门课（超过现有 $existingCount 门的 40%），已暂停自动应用"
        }
        return null
    }
}
