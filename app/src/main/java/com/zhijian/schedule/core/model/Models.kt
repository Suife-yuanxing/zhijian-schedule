package com.zhijian.schedule.core.model

import com.zhijian.schedule.core.jw.SectionTimeDraft
import kotlinx.serialization.Serializable

/**
 * 导入/新建课程的中转模型（未落库）。
 */
@Serializable
data class CourseDraft(
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekMask: String,
    val color: Int = 0,
    val courseType: Int = 0,
    val note: String = "",
)

/** 教务考试安排的中转模型（未落库） */
@Serializable
data class ExamDraft(
    val name: String,
    val room: String = "",
    val seat: String = "",
    /** 开考时刻 epoch millis */
    val examTime: Long,
    val note: String = "",
)

@Serializable
enum class ImportSource(
    val label: String,
) {
    MANUAL("手动"),
    CSV("CSV"),
    XLSX("Excel"),
    JSON("JSON"),
    WAKEUP("口令"),
    OCR("截图"),
    ICS("日历"),
    QR("二维码"),
    JW("教务"),
}

public enum class ImportChangeKind { ADDED, CHANGED, REMOVED, UNCHANGED }

data class ImportChange(
    val kind: ImportChangeKind,
    val name: String,
    val detail: String,
    val draft: CourseDraft? = null,
    val existingId: Long? = null,
    val conflict: Boolean = false,
)

data class ImportPreview(
    val source: ImportSource,
    val changes: List<ImportChange>,
    /** 自动同步护栏触发时的说明；手动预览恒为 null */
    val warning: String? = null,
    /**
     * 来源自带的上课时间。非空时确认导入会连带改写作息时间——
     * 作息不硬编码，抓到的课表写几点就几点。
     */
    val sectionTimes: List<SectionTimeDraft> = emptyList(),
) {
    val addedCount get() = changes.count { it.kind == ImportChangeKind.ADDED }
    val changedCount get() = changes.count { it.kind == ImportChangeKind.CHANGED }
    val removedCount get() = changes.count { it.kind == ImportChangeKind.REMOVED }
    val conflictCount get() = changes.count { it.conflict }

    /** 来源带了上课时间：确认导入时作息会一起被改写 */
    val hasSectionUpdate: Boolean get() = sectionTimes.isNotEmpty()

    /** 预览页顶部的作息提示；没有作息变化返回 null（不占地方） */
    fun sectionHint(): String? {
        if (!hasSectionUpdate) return null
        val first = sectionTimes.minBy { it.sectionNo }
        return "作息时间将同步更新（第 " + first.sectionNo + " 节 " + first.startTime + " 起，共 " +
            sectionTimes.size + " 节）"
    }

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (addedCount > 0) parts += "新增 $addedCount"
        if (changedCount > 0) parts += "变更 $changedCount"
        if (removedCount > 0) parts += "取消 $removedCount"
        if (conflictCount > 0) parts += "冲突 $conflictCount"
        return if (parts.isEmpty()) "无变化" else parts.joinToString(" · ")
    }
}

/** 课程类型 */
object CourseType {
    const val NORMAL = 0
    const val LAB = 1
    const val PE = 2
}

/** 深色模式设置值 */
object DarkModeSetting {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}
