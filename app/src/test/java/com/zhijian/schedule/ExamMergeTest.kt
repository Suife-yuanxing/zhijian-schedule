package com.zhijian.schedule

import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.repository.ExamMergePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamMergeTest {
    private val t1 = 1_760_000_000_000L
    private val t2 = 1_760_100_000_000L

    private fun old(id: Long, name: String, time: Long, room: String = "旧考场", manual: Boolean = false) =
        ExamEntity(id = id, tableId = 1, name = name, room = room, examTime = time, manuallyEdited = manual)

    @Test fun `教务新增_变更_取消分别归入对应操作`() {
        val existing = listOf(old(1, "高等数学", t1), old(2, "大学英语", t2))
        val fetched = listOf(
            ExamDraft(name = "高等数学", room = "教305", examTime = t1), // 变更
            ExamDraft(name = "数据结构", examTime = t2),                 // 新增
            // 大学英语消失 → 取消
        )
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(listOf("数据结构"), r.toInsert.map { it.name })
        assertEquals(listOf(1L), r.toUpdate.map { it.id })
        assertEquals("教305", r.toUpdate.single().room)
        assertEquals(listOf(2L), r.toDeleteIds)
        assertEquals(0, r.keptManual)
    }

    @Test fun `手动编辑的考试永不覆盖也不删除`() {
        val existing = listOf(old(1, "高等数学", t1, manual = true), old(2, "大学英语", t2))
        val fetched = listOf(
            ExamDraft(name = "高等数学", room = "教888", examTime = t1),
            ExamDraft(name = "数据结构", examTime = t2),
        )
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(0, r.toUpdate.size)          // 手动项不更新
        assertEquals(listOf(2L), r.toDeleteIds)   // 非手动且教务已消失的正常取消
        assertEquals(1, r.keptManual)
    }

    @Test fun `无变化时changed为false且摘要为无变化`() {
        val existing = listOf(old(1, "高等数学", t1, room = "教305"))
        val fetched = listOf(ExamDraft(name = "高等数学", room = "教305", examTime = t1))
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(false, r.changed)
        assertEquals("考试安排无变化", r.summaryText())
    }
}
