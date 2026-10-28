package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.jw.SectionTimes

/**
 * 把「抓到的作息」落成新作息的决策：哪些小节沿用页面、哪些沿用本地、哪些顺推补出来。
 *
 * 单独抽成纯函数，是为了让「抓课表 → 作息变」这条链在没有数据库的情况下可测：
 * 库里多半只有 12 小节默认值，而抓到的时间可能只覆盖前 8 节，剩下的必须原样留住。
 */
object SectionTimePlan {
    /**
     * @param current 本地现有作息（可能为空——新装用户还没生成过）
     * @param incoming 本次抓取/识别到的作息；为空表示来源没有时间信息，返回 null
     * @param courses 本次要落库的课程，用来决定至少需要多少小节
     * @return 新的完整作息；返回 null 时调用方不应改动数据库
     */
    fun plan(
        current: List<SectionTimeEntity>,
        incoming: List<SectionTimeDraft>,
        courses: List<CourseEntity>,
        sectionsPerPeriod: Int = SectionTimes.SECTIONS_PER_PERIOD,
    ): List<SectionTimeEntity>? =
        plan(
            current = current,
            incoming = incoming,
            neededEndSection = courses.maxOfOrNull { it.endSection } ?: 0,
            sectionsPerPeriod = sectionsPerPeriod,
        )

    /**
     * @param neededEndSection 本次要落库的课程里最大的结束节次（没有课程时传 0）
     */
    fun plan(
        current: List<SectionTimeEntity>,
        incoming: List<SectionTimeDraft>,
        neededEndSection: Int,
        sectionsPerPeriod: Int = SectionTimes.SECTIONS_PER_PERIOD,
    ): List<SectionTimeEntity>? {
        if (incoming.isEmpty()) return null
        val modeId = current.firstOrNull()?.modeId ?: 0L
        val needed =
            maxOf(
                incoming.maxOf { it.sectionNo },
                current.maxOfOrNull { it.sectionNo } ?: 0,
                neededEndSection,
                1,
            ).coerceAtMost(SectionTimes.MAX_SECTIONS)
        val fallback = current.map { SectionTimeDraft(it.sectionNo, it.startTime, it.endTime) }
        val normalized = SectionTimes.normalize(incoming, fallback, needed)
        return normalized.map { draft ->
            val existing = current.firstOrNull { it.sectionNo == draft.sectionNo }
            existing?.copy(startTime = draft.startTime, endTime = draft.endTime)
                ?: SectionTimeEntity(
                    modeId = modeId,
                    sectionNo = draft.sectionNo,
                    periodNo = (draft.sectionNo - 1) / sectionsPerPeriod.coerceAtLeast(1) + 1,
                    session = sessionOf(draft.sectionNo, sectionsPerPeriod),
                    startTime = draft.startTime,
                    endTime = draft.endTime,
                )
        }
    }

    /** 上午 / 下午的界线沿用现有会话划分：前两个大节算上午 */
    private fun sessionOf(
        sectionNo: Int,
        sectionsPerPeriod: Int,
    ): String {
        val period = (sectionNo - 1) / sectionsPerPeriod.coerceAtLeast(1) + 1
        return if (period <= 2) "AM" else "PM"
    }
}
