package com.zhijian.schedule.core.model

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

@Serializable
enum class ImportSource(val label: String) {
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
) {
    val addedCount get() = changes.count { it.kind == ImportChangeKind.ADDED }
    val changedCount get() = changes.count { it.kind == ImportChangeKind.CHANGED }
    val removedCount get() = changes.count { it.kind == ImportChangeKind.REMOVED }
    val conflictCount get() = changes.count { it.conflict }

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
