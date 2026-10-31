package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「取消」口径的决策层：手动导入整表替换、自动同步保守取消。
 *
 * 背景：手动导入（教务抓取 / CSV / 截图…）原本从不生成「取消」，旧课只能靠自动同步
 * 清理——结果是「有课程信息时再导入，新课直接摞在旧课上面」。改成替换语义后，
 * 未被新数据认领的本地课在预览里全部标取消，确认即覆盖。
 */
class ImportReplaceTest {
    private fun course(
        id: Long,
        name: String,
        manuallyEdited: Boolean = false,
    ) = CourseEntity(
        id = id,
        tableId = 1L,
        name = name,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        weekMask = "1".repeat(20),
        color = 0,
        manuallyEdited = manuallyEdited,
    )

    @Test
    fun `manual import replaces every unmatched course`() {
        val existing = listOf(course(1, "高等数学"), course(2, "旧课A"), course(3, "旧课B"))
        val removals =
            ImportReplace.removalCandidates(
                existing,
                matchedIds = setOf(1L),
                syncMode = false,
                firstSync = true,
            )
        assertEquals(listOf("旧课A", "旧课B"), removals.map { it.name })
    }

    /** 手动编辑过的课也在替换范围内：导入 = 换课表，预览里逐条列名，确认即覆盖 */
    @Test
    fun `manual import removes hand tuned courses too`() {
        val existing = listOf(course(1, "手改课", manuallyEdited = true))
        val removals = ImportReplace.removalCandidates(existing, emptySet(), syncMode = false, firstSync = false)
        assertTrue(removals.any { it.manuallyEdited })
    }

    /** 自动同步保持原护栏：首次一条不删；之后手动编辑过的保留 */
    @Test
    fun `auto sync stays conservative`() {
        val existing = listOf(course(1, "手改课", manuallyEdited = true), course(2, "旧课A"))
        assertEquals(
            emptyList<CourseEntity>(),
            ImportReplace.removalCandidates(existing, emptySet(), syncMode = true, firstSync = true),
        )
        val removals = ImportReplace.removalCandidates(existing, emptySet(), syncMode = true, firstSync = false)
        assertEquals(listOf("旧课A"), removals.map { it.name })
    }

    /** 已被新数据认领（新增/变更/无变化）的课不再标取消 */
    @Test
    fun `matched courses are never removed`() {
        val existing = listOf(course(1, "高等数学"), course(2, "旧课A"))
        val removals =
            ImportReplace.removalCandidates(existing, matchedIds = setOf(1L, 2L), syncMode = false, firstSync = false)
        assertTrue(removals.isEmpty())
    }
}
