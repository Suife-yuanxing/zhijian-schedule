package com.zhijian.schedule.core.repository

import androidx.room.withTransaction
import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ImportSnapshotEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.JwMeta
import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ImportChange
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.util.AdjacentCourseMerge
import com.zhijian.schedule.core.util.WeekMask
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
     * @param syncMode true 表示教务自动同步（取消保守、护栏兜底）；false 表示手动导入：
     *   整表替换——未被新数据认领的本地课在预览里全部标「取消」，确认后直接覆盖
     * @param meta 来源自带的学期元数据；非空时确认导入会连课表信息一起同步
     */
    suspend fun buildPreview(
        drafts: List<CourseDraft>,
        source: ImportSource,
        syncMode: Boolean = false,
        sectionTimes: List<SectionTimeDraft> = emptyList(),
        meta: JwMeta? = null,
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
            val matched = matchExisting(existing, matchedIds, normalized)
            if (matched != null) matchedIds += matched.id
            changes += diffOf(normalized, matched)
        }

        ImportReplace.removalCandidates(existing, matchedIds, syncMode, firstJwSync).forEach { old ->
            changes +=
                ImportChange(
                    kind = ImportChangeKind.REMOVED,
                    name = old.name,
                    // 手动替换里手动编辑过的课也会被覆盖，在预览里说清楚，别让用户事后才发现
                    detail = courseSummary(old.toDraft()) + if (old.manuallyEdited) "（手动编辑过）" else "",
                    existingId = old.id,
                )
        }
        val warning =
            if (syncMode && source == ImportSource.JW) {
                ImportGuard.evaluate(
                    existingCount = existing.size,
                    addedCount = changes.count { it.kind == ImportChangeKind.ADDED },
                    removedCount = changes.count { it.kind == ImportChangeKind.REMOVED },
                    firstSync = firstJwSync,
                )
            } else {
                null
            }
        val infoHint =
            when {
                source != ImportSource.JW -> null
                meta != null -> metaHint(tableId, meta)
                else -> auditInfo(tableId)
            }
        return ImportPreview(source, changes, warning, sectionTimes, meta, infoHint)
    }

    /** 元数据同步提示：只列真正会变的字段，与教务声明一致的本地值不占用户注意力 */
    private suspend fun metaHint(
        tableId: Long,
        meta: JwMeta,
    ): String? {
        val table = db.timetableDao().getById(tableId) ?: return null
        val planned = TimetableMetaPlan.apply(table, meta)
        if (planned == table) return null
        val parts = mutableListOf<String>()
        if (planned.semester != table.semester) parts += "学期「${planned.semester}」"
        if (planned.startDate != table.startDate) parts += "第 1 周 ${planned.startDate}"
        if (planned.totalWeeks != table.totalWeeks) parts += "总周数 ${planned.totalWeeks}"
        if (parts.isEmpty()) return null
        return "课表信息将随抓取同步：" + parts.joinToString("，")
    }

    /**
     * 课表信息核对提示：只在教务抓取时给。
     *
     * 学期 / 开学日期 / 总周数是本地元数据，抓取不会改写（写坏了整学期周次映射都会错），
     * 代价是换学校或学期后旧值会安静地留着。手动导入（CSV / 截图 / 文本）由用户自己挑
     * 文件，不存在这个问题，不打扰。
     */
    private suspend fun auditInfo(tableId: Long): String? {
        val settings = settingsStore.settings.first()
        val table = db.timetableDao().getById(tableId)
        val syncedHost = settings.jwSyncedHost
        val schoolChanged =
            syncedHost.isNotBlank() && syncedHost != TimetableInfoAudit.hostOf(settings.jwUrl)
        return TimetableInfoAudit.audit(table?.semester.orEmpty(), schoolChanged)
    }

    /** 同一天同一门课优先按起始节次认，其次按「同名同日」认（节次被挪过的变更） */
    private fun matchExisting(
        existing: List<CourseEntity>,
        matchedIds: Set<Long>,
        draft: CourseDraft,
    ): CourseEntity? {
        val candidates =
            existing.filter {
                it.id !in matchedIds && it.name == draft.name && it.dayOfWeek == draft.dayOfWeek
            }
        return candidates.firstOrNull { it.startSection == draft.startSection } ?: candidates.firstOrNull()
    }

    /** 一门草稿相对本地记录的变更类型与说明 */
    private fun diffOf(
        draft: CourseDraft,
        matched: CourseEntity?,
    ): ImportChange {
        if (matched == null) {
            return ImportChange(
                kind = ImportChangeKind.ADDED,
                name = draft.name,
                detail = courseSummary(draft),
                draft = draft,
            )
        }
        val same =
            matched.room == draft.room &&
                matched.teacher == draft.teacher &&
                matched.startSection == draft.startSection &&
                matched.endSection == draft.endSection &&
                matched.weekMask == draft.weekMask
        return if (same) {
            ImportChange(
                kind = ImportChangeKind.UNCHANGED,
                name = draft.name,
                detail = "",
                existingId = matched.id,
            )
        } else {
            ImportChange(
                kind = ImportChangeKind.CHANGED,
                name = draft.name,
                detail = courseDiffLine(matched, draft),
                draft = draft,
                existingId = matched.id,
                conflict = matched.manuallyEdited,
            )
        }
    }

    /** 应用导入：新增/变更（手动编辑冲突跳过）/取消 */
    suspend fun applyImport(
        preview: ImportPreview,
        applyConflicts: Boolean = false,
    ) {
        val tableId = currentTableId() ?: return
        preview.changes.forEach { change ->
            when (change.kind) {
                ImportChangeKind.ADDED ->
                    change.draft?.let { d ->
                        db.courseDao().insert(
                            d.toEntity(tableId, if (d.color == 0) defaultCourseColor(d.name) else d.color),
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
                        ),
                    )
                }
                ImportChangeKind.REMOVED -> change.existingId?.let { db.courseDao().deleteById(it) }
                ImportChangeKind.UNCHANGED -> Unit
            }
        }
        // 作息跟着课表一起变：来源写了时间就以来源为准（不硬编码）
        applySectionTimes(preview)
        // 课表信息同理：教务声明了学期 / 开学日期 / 总周数就一起同步
        applyMeta(preview.meta)
        // 手动导入不会清理旧记录：把新旧数据里残留的连堂课再归并一次
        mergeAdjacentCourses(tableId)
        val drafts = preview.changes.mapNotNull { it.draft }
        if (drafts.isNotEmpty()) saveSnapshot(preview.source, drafts)
    }

    /** 学期元数据落库；总周数变了就把本表课程掩码重排到新长度，边界周才不会读错 */
    private suspend fun applyMeta(meta: JwMeta?) {
        if (meta == null) return
        val tableId = currentTableId() ?: return
        val table = db.timetableDao().getById(tableId) ?: return
        val planned = TimetableMetaPlan.apply(table, meta)
        if (planned == table) return
        db.timetableDao().update(planned)
        if (planned.totalWeeks != table.totalWeeks) {
            db.courseDao().getByTable(tableId).forEach { course ->
                val normalized = WeekMask.normalize(course.weekMask, planned.totalWeeks)
                if (normalized != course.weekMask) {
                    db.courseDao().update(course.copy(weekMask = normalized))
                }
            }
        }
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
    suspend fun saveSnapshot(
        source: ImportSource,
        drafts: List<CourseDraft>,
    ) {
        val tableId = currentTableId() ?: return
        db.importSnapshotDao().insert(
            ImportSnapshotEntity(
                tableId = tableId,
                payload = json.encodeToString(drafts),
                source = source.name,
            ),
        )
        // 只保留最近 20 份快照
        val keep = db.importSnapshotDao().keepLatest(tableId, 20)
        if (keep.isNotEmpty()) {
            db.importSnapshotDao().deleteExcept(tableId, keep.map { it.id })
        }
    }

    /**
     * 把预览里来源带的作息写进当前课表；来源没带时间就原样保留用户的作息。
     */
    suspend fun applySectionTimes(preview: ImportPreview) {
        val incoming = preview.sectionTimes
        if (incoming.isEmpty()) return
        val tableId = currentTableId()
        val mode = tableId?.let { db.timeModeDao().getPrimaryMode(it) }
        if (mode != null) {
            val current = db.timeModeDao().getSections(mode.id)
            val neededEnd = preview.changes.mapNotNull { it.draft?.endSection }.maxOrNull() ?: 0
            SectionTimePlan.plan(current, incoming, neededEnd)?.let { plan ->
                writeSections(current, plan)
            }
        }
    }

    /** 更新已有小节、补齐新增小节（作息比本地更长时） */
    private suspend fun writeSections(
        current: List<SectionTimeEntity>,
        plan: List<SectionTimeEntity>,
    ) {
        val existing = current.mapTo(HashSet()) { it.sectionNo }
        db.withTransaction {
            plan.forEach { section ->
                if (section.sectionNo in existing) {
                    db.timeModeDao().updateSection(section)
                } else {
                    db.timeModeDao().insertSections(listOf(section))
                }
            }
        }
    }

    private suspend fun sectionsFor(tableId: Long): List<SectionTimeEntity> {
        val mode = db.timeModeDao().getPrimaryMode(tableId) ?: return emptyList()
        return db.timeModeDao().getSections(mode.id)
    }
}
