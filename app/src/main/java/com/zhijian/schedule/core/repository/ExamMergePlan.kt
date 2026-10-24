package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.model.ExamDraft

/**
 * 教务考试合并决策（纯逻辑，可单测）。匹配键 = 课程名 + 开考时刻。
 * 手动编辑（manuallyEdited）的考试永不更新、永不删除，只计数进摘要。
 */
object ExamMergePlan {

    data class Result(
        val toInsert: List<ExamEntity> = emptyList(),
        val toUpdate: List<ExamEntity> = emptyList(),
        val toDeleteIds: List<Long> = emptyList(),
        /** 手动编辑被保留原样的场数 */
        val keptManual: Int = 0,
    ) {
        val changed: Boolean
            get() = toInsert.isNotEmpty() || toUpdate.isNotEmpty() || toDeleteIds.isNotEmpty()

        fun summaryText(): String = buildList {
            if (toInsert.isNotEmpty()) add("新增 ${toInsert.size}")
            if (toUpdate.isNotEmpty()) add("更新 ${toUpdate.size}")
            if (toDeleteIds.isNotEmpty()) add("取消 ${toDeleteIds.size}")
            if (keptManual > 0) add("手动 $keptManual 场保留")
        }.joinToString(" · ").ifEmpty { "考试安排无变化" }
    }

    fun plan(existing: List<ExamEntity>, fetched: List<ExamDraft>): Result {
        val fetchedByKey = fetched.associateBy { it.name to it.examTime }
        val existingByKey = existing.associateBy { it.name to it.examTime }
        val toInsert = mutableListOf<ExamEntity>()
        val toUpdate = mutableListOf<ExamEntity>()
        var keptManual = 0
        fetchedByKey.forEach { (key, draft) ->
            val old = existingByKey[key]
            when {
                old == null -> toInsert += ExamEntity(
                    tableId = 0, // 落库时由 Repository 补 tableId
                    name = draft.name,
                    room = draft.room,
                    seat = draft.seat,
                    examTime = draft.examTime,
                    note = draft.note,
                )
                old.manuallyEdited -> keptManual++
                old.room != draft.room || old.seat != draft.seat || old.note != draft.note ->
                    toUpdate += old.copy(room = draft.room, seat = draft.seat, note = draft.note)
            }
        }
        val toDelete = existing
            .filter { !it.manuallyEdited && (it.name to it.examTime) !in fetchedByKey }
            .map { it.id }
        return Result(toInsert, toUpdate, toDelete, keptManual)
    }
}
