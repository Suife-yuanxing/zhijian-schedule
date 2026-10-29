package com.zhijian.schedule.core.util

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.model.CourseDraft
import java.time.Duration

/**
 * 教务/截图来源会把连堂的同一门课按大节拆成多条（例如 5-6 节与 7-8 节）。
 * 这里把同一天、同一课程、节次首尾相接且课间不超过 30 分钟的记录并成一条，
 * 让课表按一条连续课块渲染，而不是两张各带标题与周次的卡片。
 *
 * 教室/教师允许一方为空（取非空值补齐）；周次、颜色、课程类型、备注必须一致，
 * 避免把不同周次或不同教学班误并。
 */
object AdjacentCourseMerge {
    private const val MAX_BREAK_MINUTES = 30L

    fun drafts(
        drafts: List<CourseDraft>,
        totalWeeks: Int,
        sections: List<SectionTimeEntity>,
    ): List<CourseDraft> {
        val ordered =
            drafts
                .map { it.copy(weekMask = WeekMask.normalize(it.weekMask, totalWeeks)) }
                .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
        if (ordered.size < 2) return ordered

        val out = mutableListOf<CourseDraft>()
        ordered.forEach { next ->
            val index = out.indexOfLast { prev -> prev.sameLessonAs(next) }
            if (index >= 0 && out[index].continuesWith(next, sections)) {
                val prev = out[index]
                out[index] =
                    prev.copy(
                        endSection = next.endSection,
                        room = prev.room.ifBlank { next.room },
                        teacher = prev.teacher.ifBlank { next.teacher },
                    )
            } else {
                out += next
            }
        }
        return out
    }

    fun entities(
        courses: List<CourseEntity>,
        totalWeeks: Int,
        sections: List<SectionTimeEntity>,
    ): List<CourseEntity> {
        if (courses.size < 2) return courses
        val ordered = courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.id }))

        val out = mutableListOf<CourseEntity>()
        ordered.forEach { next ->
            val index = out.indexOfLast { prev -> prev.sameLessonAs(next, totalWeeks) }
            if (index >= 0 && out[index].continuesWith(next, sections)) {
                val prev = out[index]
                out[index] =
                    prev.copy(
                        endSection = next.endSection,
                        room = prev.room.ifBlank { next.room },
                        teacher = prev.teacher.ifBlank { next.teacher },
                        manuallyEdited = prev.manuallyEdited || next.manuallyEdited,
                    )
            } else {
                out += next
            }
        }
        return out
    }

    private fun CourseDraft.sameLessonAs(other: CourseDraft): Boolean =
        dayOfWeek == other.dayOfWeek &&
            name == other.name &&
            weekMask == other.weekMask &&
            color == other.color &&
            courseType == other.courseType &&
            note == other.note &&
            compatible(room, other.room) &&
            compatible(teacher, other.teacher)

    private fun CourseEntity.sameLessonAs(
        other: CourseEntity,
        totalWeeks: Int,
    ): Boolean =
        tableId == other.tableId &&
            dayOfWeek == other.dayOfWeek &&
            name == other.name &&
            WeekMask.normalize(weekMask, totalWeeks) == WeekMask.normalize(other.weekMask, totalWeeks) &&
            color == other.color &&
            courseType == other.courseType &&
            note == other.note &&
            compatible(room, other.room) &&
            compatible(teacher, other.teacher)

    private fun CourseDraft.continuesWith(
        next: CourseDraft,
        sections: List<SectionTimeEntity>,
    ): Boolean =
        next.startSection == endSection + 1 &&
            bridgeAllowed(endSection, next.startSection, sections)

    private fun CourseEntity.continuesWith(
        next: CourseEntity,
        sections: List<SectionTimeEntity>,
    ): Boolean =
        next.startSection == endSection + 1 &&
            bridgeAllowed(endSection, next.startSection, sections)

    /** 课间要短到看起来是连堂；午休、晚饭这种大段间隔不能并 */
    private fun bridgeAllowed(
        endSection: Int,
        startSection: Int,
        sections: List<SectionTimeEntity>,
    ): Boolean {
        val end =
            sections
                .firstOrNull { it.sectionNo == endSection }
                ?.endTime
                ?.let(TimeUtils::parseTime)
                ?: return false
        val start =
            sections
                .firstOrNull { it.sectionNo == startSection }
                ?.startTime
                ?.let(TimeUtils::parseTime)
                ?: return false
        return Duration.between(end, start).toMinutes() in 0..MAX_BREAK_MINUTES
    }

    private fun compatible(
        a: String,
        b: String,
    ): Boolean = a.isBlank() || b.isBlank() || a == b
}
