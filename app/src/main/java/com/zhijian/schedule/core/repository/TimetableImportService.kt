package com.zhijian.schedule.core.repository

import androidx.room.withTransaction
import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.ImportSnapshotEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ImportChange
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.util.AdjacentCourseMerge
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 导入 / 教务同步的流水线：Diff 生成预览、应用变更、保存快照、归并连堂课。
 *
 * 由 [ScheduleRepository] 持有并对外转发；不注册到 DI 容器，避免调用方绕过仓库直接用。
 */
internal class TimetableImportService(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {

    /** 当前生效课表 id（activeTableId 失效时回落到第一条）。 */
    suspend fun currentTableId(): Long? {
        val s = settingsStore.settings.first()
        val list = db.timetableDao().getAll()
        return list.firstOrNull { it.id == s.activeTableId }?.id ?: list.firstOrNull()?.id
    }

    /**
     * 与本地数据 Diff，生成预览（方案 4.6 统一流程 / 4.10 Diff）。
     * @param syncMode true 表示教务自动同步：会检测「取消」的课程并标记 manual 冲突
     */
    suspend fun buildPreview(
        drafts: List<CourseDraft>,
        source: ImportSource,
        syncMode: Boolean = false,
    ): ImportPreview {
        val tableId = currentTableId() ?: return ImportPreview(source, emptyList())
        val totalWeeks = db.timetableDao().getById(tableId)?.totalWeeks ?: 20
        // 首次教务同步只建立基线、不删除任何旧课；lastSyncTime 在抓取成功后才会写入
        val firstJwSync = settingsStore.settings.first().lastSyncTime == 0L
        val existing = db.courseDao().getByTable(tableId)
        val changes = mutableListOf<ImportChange>()
        val matchedIds = mutableSetOf<Long>()

        // 教务按大节返回的连堂课先并成一条，预览与落库都不会再拆开
        AdjacentCourseMerge.drafts(drafts, totalWeeks, sectionsFor(tableId)).forEach { normalized ->
            val exact = existing.firstOrNull {
                !matchedIds.contains(it.id) &&
                    it.name == normalized.name &&
                    it.dayOfWeek == normalized.dayOfWeek &&
                    it.startSection == normalized.startSection
            }
            val overlapped = exact ?: existing.firstOrNull {
                !matchedIds.contains(it.id) &&
                    it.name == normalized.name &&
                    it.dayOfWeek == normalized.dayOfWeek
            }
            if (overlapped == null) {
                changes += ImportChange(
                    kind = ImportChangeKind.ADDED,
                    name = normalized.name,
                    detail = courseSummary(normalized),
                    draft = normalized,
                )
            } else {
                matchedIds += overlapped.id
                val same = overlapped.room == normalized.room &&
                    overlapped.teacher == normalized.teacher &&
                    overlapped.startSection == normalized.startSection &&
                    overlapped.endSection == normalized.endSection &&
                    overlapped.weekMask == normalized.weekMask
                if (same) {
                    changes += ImportChange(
                        kind = ImportChangeKind.UNCHANGED,
                        name = normalized.name,
                        detail = "",
                        existingId = overlapped.id,
                    )
                } else {
                    changes += ImportChange(
                        kind = ImportChangeKind.CHANGED,
                        name = normalized.name,
                        detail = courseDiffLine(overlapped, normalized),
                        draft = normalized,
                        existingId = overlapped.id,
                        conflict = overlapped.manuallyEdited,
                    )
                }
            }
        }

        if (syncMode && !firstJwSync) {
            existing.filter { !matchedIds.contains(it.id) && !it.manuallyEdited }.forEach { old ->
                changes += ImportChange(
                    kind = ImportChangeKind.REMOVED,
                    name = old.name,
                    detail = courseSummary(old.toDraft()),
                    existingId = old.id,
                )
            }
        }
        val warning = if (syncMode && source == ImportSource.JW) {
            ImportGuard.evaluate(
                existingCount = existing.size,
                addedCount = changes.count { it.kind == ImportChangeKind.ADDED },
                removedCount = changes.count { it.kind == ImportChangeKind.REMOVED },
                firstSync = firstJwSync,
            )
        } else {
            null
        }
        return ImportPreview(source, changes, warning)
    }

    /** 应用导入：新增/变更（手动编辑冲突跳过）/取消 */
    suspend fun applyImport(preview: ImportPreview, applyConflicts: Boolean = false) {
        val tableId = currentTableId() ?: return
        preview.changes.forEach { change ->
            when (change.kind) {
                ImportChangeKind.ADDED -> change.draft?.let { d ->
                    db.courseDao().insert(
                        d.toEntity(tableId, if (d.color == 0) defaultCourseColor(d.name) else d.color)
                    )
                }
                ImportChangeKind.CHANGED -> {
                    if (change.conflict && !applyConflicts) return@forEach
                    val draft = change.draft ?: return@forEach
                    val id = change.existingId ?: return@forEach
                    val old = db.courseDao().getById(id) ?: return@forEach
                    db.courseDao().update(
                        old.copy(
                            teacher = draft.teacher,
                            room = draft.room,
                            startSection = draft.startSection,
                            endSection = draft.endSection,
                            weekMask = draft.weekMask,
                        )
                    )
                }
                ImportChangeKind.REMOVED -> change.existingId?.let { db.courseDao().deleteById(it) }
                ImportChangeKind.UNCHANGED -> Unit
            }
        }
        // 手动导入不会清理旧记录：把新旧数据里残留的连堂课再归并一次
        mergeAdjacentCourses(tableId)
        val drafts = preview.changes.mapNotNull { it.draft }
        if (drafts.isNotEmpty()) saveSnapshot(preview.source, drafts)
    }

    /**
     * 把同一门课被拆开的相邻节次并成一条，保留最早那条并延长它的结束节次。
     * 只合并节次首尾相接且课间不超过 30 分钟的记录，午休/晚饭不会误并。
     */
    suspend fun mergeAdjacentCourses(tableId: Long) {
        val totalWeeks = db.timetableDao().getById(tableId)?.totalWeeks ?: return
        val sections = sectionsFor(tableId)
        if (sections.isEmpty()) return
        val before = db.courseDao().getByTable(tableId)
        if (before.size < 2) return
        val after = AdjacentCourseMerge.entities(before, totalWeeks, sections)
        if (after.size == before.size) return

        val beforeById = before.associateBy { it.id }
        val survivorIds = after.mapTo(HashSet()) { it.id }
        db.withTransaction {
            after.forEach { merged ->
                if (beforeById[merged.id] != merged) db.courseDao().update(merged)
            }
            before.forEach { old ->
                if (old.id !in survivorIds) db.courseDao().deleteById(old.id)
            }
        }
    }

    /** 保存导入快照，只保留最近 20 份。 */
    suspend fun saveSnapshot(source: ImportSource, drafts: List<CourseDraft>) {
        val tableId = currentTableId() ?: return
        db.importSnapshotDao().insert(
            ImportSnapshotEntity(
                tableId = tableId,
                payload = json.encodeToString(drafts),
                source = source.name,
            )
        )
        // 只保留最近 20 份快照
        val keep = db.importSnapshotDao().keepLatest(tableId, 20)
        if (keep.isNotEmpty()) {
            db.importSnapshotDao().deleteExcept(tableId, keep.map { it.id })
        }
    }

    private suspend fun sectionsFor(tableId: Long): List<SectionTimeEntity> {
        val mode = db.timeModeDao().getPrimaryMode(tableId) ?: return emptyList()
        return db.timeModeDao().getSections(mode.id)
    }
}
