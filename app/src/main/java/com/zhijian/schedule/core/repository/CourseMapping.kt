package com.zhijian.schedule.core.repository

import androidx.compose.ui.graphics.toArgb
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import kotlin.math.abs

/**
 * 课程草稿 / 数据库实体之间的映射，以及导入预览所需的摘要与差异文案。
 *
 * 全部为无状态纯函数，便于单测；不持有数据库或协程上下文。
 */

/** 草稿未指定颜色时的稳定取色：按课名哈希取 [ThemePresets.paper] 色板。 */
internal fun defaultCourseColor(name: String): Int {
    val palette = ThemePresets.paper.coursePalette
    return palette[abs(name.hashCode()) % palette.size].toArgb()
}

/** 草稿 -> 实体。颜色由调用方解析后传入，保证「新增取哈希色、编辑保留旧色」两种语义各自成立。 */
internal fun CourseDraft.toEntity(
    tableId: Long,
    color: Int,
    manuallyEdited: Boolean = false,
): CourseEntity = CourseEntity(
    tableId = tableId,
    name = name,
    teacher = teacher,
    room = room,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    weekMask = weekMask,
    color = color,
    courseType = courseType,
    manuallyEdited = manuallyEdited,
    note = note,
)

/** 实体 -> 草稿。 */
internal fun CourseEntity.toDraft(): CourseDraft = CourseDraft(
    name = name,
    teacher = teacher,
    room = room,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    weekMask = weekMask,
    color = color,
    courseType = courseType,
    note = note,
)

/** 导入预览里「新增 / 取消」条目的摘要行。 */
internal fun courseSummary(draft: CourseDraft): String {
    val parts = mutableListOf<String>()
    parts += TimeUtils.dayName(draft.dayOfWeek)
    parts += "第${draft.startSection}-${draft.endSection}节"
    if (draft.room.isNotBlank()) parts += draft.room
    parts += WeekMask.toExpression(draft.weekMask)
    return parts.joinToString(" · ")
}

/** 导入预览里「变更」条目的差异行（教室 / 教师 / 节次 / 周次）。 */
internal fun courseDiffLine(old: CourseEntity, new: CourseDraft): String {
    val parts = mutableListOf<String>()
    if (old.room != new.room) parts += "教室 ${old.room.ifBlank { "—" }} → ${new.room.ifBlank { "—" }}"
    if (old.teacher != new.teacher) parts += "教师 ${old.teacher.ifBlank { "—" }} → ${new.teacher.ifBlank { "—" }}"
    if (old.startSection != new.startSection || old.endSection != new.endSection) {
        parts += "节次 ${old.startSection}-${old.endSection} → ${new.startSection}-${new.endSection}"
    }
    if (old.weekMask != new.weekMask) {
        parts += "周次 ${WeekMask.toExpression(old.weekMask)} → ${WeekMask.toExpression(new.weekMask)}"
    }
    return parts.joinToString("；")
}
