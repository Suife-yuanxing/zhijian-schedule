package com.zhijian.schedule.core.repository

import androidx.compose.ui.graphics.toArgb
import androidx.room.withTransaction
import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.db.ImportSnapshotEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimeModeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.model.ImportChange
import com.zhijian.schedule.core.model.ImportChangeKind
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.util.AdjacentCourseMerge
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ScheduleRepository @Inject constructor(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    val settings: Flow<AppSettings> = settingsStore.settings

    val timetables: Flow<List<TimetableEntity>> = db.timetableDao().observeAll()

    val activeTimetable: Flow<TimetableEntity?> =
        combine(settings, timetables) { s, list ->
            list.firstOrNull { it.id == s.activeTableId } ?: list.firstOrNull()
        }

    val activeCourses: Flow<List<CourseEntity>> = activeTimetable.flatMapLatest { t ->
        if (t == null) flowOf(emptyList()) else db.courseDao().observeByTable(t.id)
    }

    val activeMode: Flow<TimeModeEntity?> = activeTimetable.flatMapLatest { t ->
        if (t == null) flowOf(null) else db.timeModeDao().observeModes(t.id)
            .map { modes -> modes.firstOrNull() }
    }

    val activeSections: Flow<List<SectionTimeEntity>> = activeMode.flatMapLatest { mode ->
        if (mode == null) flowOf(emptyList()) else db.timeModeDao().observeSections(mode.id)
    }

    val activeExams = activeTimetable.flatMapLatest { t ->
        if (t == null) flowOf(emptyList()) else db.examDao().observeByTable(t.id)
    }

    // ---------- 初始化 ----------

    /** 首次启动创建默认课表（本周一为第 1 周起点），并保证 activeTableId 有效 */
    suspend fun ensureInitialized() {
        val existing = db.timetableDao().getAll()
        if (existing.isEmpty()) {
            val monday = LocalDate.now().with(DayOfWeek.MONDAY)
            val id = createTimetable("我的课表", "春季学期", monday, 20)
            settingsStore.setActiveTable(id)
        } else {
            // 若 activeTableId 无效则指向第一张课表
            val currentId = settingsStore.settings.first().activeTableId
            if (existing.none { it.id == currentId }) {
                settingsStore.setActiveTable(existing.first().id)
            }
        }
        // 旧数据里教务连堂课可能被拆成多条，启动时归并一次，之后课表按连续课块渲染
        db.timetableDao().getAll().forEach { mergeAdjacentCourses(it.id) }
    }

    suspend fun createTimetable(
        name: String,
        semester: String,
        startDate: LocalDate,
        totalWeeks: Int,
    ): Long {
        val dao = db.timetableDao()
        val tableId = dao.insert(
            TimetableEntity(
                name = name,
                semester = semester,
                startDate = TimeUtils.formatDate(startDate),
                totalWeeks = totalWeeks,
            )
        )
        val modeId = db.timeModeDao().insertMode(
            TimeModeEntity(tableId = tableId, weekRange = "1-$totalWeeks")
        )
        db.timeModeDao().insertSections(defaultSections(modeId))
        return tableId
    }

    companion object {
        /** 默认作息（方案 4.9 ②）：45+5+45 */
        fun defaultSections(modeId: Long): List<SectionTimeEntity> {
            val times = listOf(
                // 大节 1
                Triple(1, "08:20", "09:05"), Triple(2, "09:10", "09:55"),
                // 大节 2
                Triple(3, "10:10", "10:55"), Triple(4, "11:00", "11:45"),
                // 大节 3
                Triple(5, "14:20", "15:05"), Triple(6, "15:10", "15:55"),
                // 大节 4
                Triple(7, "16:05", "16:50"), Triple(8, "16:55", "17:40"),
                // 大节 5
                Triple(9, "18:20", "19:05"), Triple(10, "19:10", "19:55"),
                // 大节 6
                Triple(11, "20:00", "20:45"), Triple(12, "20:50", "21:35"),
            )
            return times.map { (no, start, end) ->
                val period = (no + 1) / 2
                SectionTimeEntity(
                    modeId = modeId,
                    sectionNo = no,
                    periodNo = period,
                    session = if (period <= 2) "AM" else "PM",
                    startTime = start,
                    endTime = end,
                )
            }
        }
    }

    // ---------- 课程 CRUD ----------

    suspend fun saveCourse(draft: CourseDraft, courseId: Long? = null, manuallyEdited: Boolean = true): Long {
        val tableId = currentTableId() ?: return -1
        return if (courseId == null) {
            db.courseDao().insert(
                CourseEntity(
                    tableId = tableId,
                    name = draft.name,
                    teacher = draft.teacher,
                    room = draft.room,
                    dayOfWeek = draft.dayOfWeek,
                    startSection = draft.startSection,
                    endSection = draft.endSection,
                    weekMask = draft.weekMask,
                    color = if (draft.color == 0) assignColor(draft.name) else draft.color,
                    courseType = draft.courseType,
                    manuallyEdited = manuallyEdited,
                    note = draft.note,
                )
            )
        } else {
            val old = db.courseDao().getById(courseId) ?: return -1
            db.courseDao().update(
                old.copy(
                    name = draft.name,
                    teacher = draft.teacher,
                    room = draft.room,
                    dayOfWeek = draft.dayOfWeek,
                    startSection = draft.startSection,
                    endSection = draft.endSection,
                    weekMask = draft.weekMask,
                    color = if (draft.color == 0) old.color else draft.color,
                    courseType = draft.courseType,
                    manuallyEdited = manuallyEdited,
                    note = draft.note,
                )
            )
            courseId
        }
    }

    suspend fun deleteCourse(id: Long) = db.courseDao().deleteById(id)

    suspend fun getCourse(id: Long): CourseEntity? = db.courseDao().getById(id)

    suspend fun updateTimetable(timetable: TimetableEntity) = db.timetableDao().update(timetable)

    suspend fun updateSectionTime(section: SectionTimeEntity) = db.timeModeDao().updateSection(section)

    // ---------- 考试 ----------

    /** 手动保存考试；新建或编辑均视为手动，教务同步永不覆盖 */
    suspend fun saveExam(
        name: String,
        room: String,
        seat: String,
        examTime: Long,
        note: String,
        examId: Long? = null,
    ): Long {
        val tableId = currentTableId() ?: return -1
        return if (examId == null) {
            db.examDao().insert(
                ExamEntity(
                    tableId = tableId, name = name, room = room, seat = seat,
                    examTime = examTime, note = note, manuallyEdited = true,
                )
            )
        } else {
            val old = db.examDao().getById(examId) ?: return -1
            db.examDao().update(
                old.copy(
                    name = name, room = room, seat = seat,
                    examTime = examTime, note = note, manuallyEdited = true,
                )
            )
            examId
        }
    }

    suspend fun deleteExam(id: Long) = db.examDao().deleteById(id)

    /**
     * 应用教务考试合并（键 = 课程名 + 开考时刻，见 ExamMergePlan）。
     * @return null 表示没有活动课表
     */
    suspend fun applyExamMerge(fetched: List<ExamDraft>): ExamMergePlan.Result? {
        val tableId = currentTableId() ?: return null
        val result = ExamMergePlan.plan(db.examDao().getByTable(tableId), fetched)
        if (!result.changed) return result
        db.withTransaction {
            result.toInsert.forEach { db.examDao().insert(it.copy(tableId = tableId)) }
            result.toUpdate.forEach { db.examDao().update(it) }
            result.toDeleteIds.forEach { db.examDao().deleteById(it) }
        }
        return result
    }

    fun assignColor(name: String): Int {
        val palette = ThemePresets.paper.coursePalette
        return palette[abs(name.hashCode()) % palette.size].toArgb()
    }

    suspend fun currentTableId(): Long? {
        val s = settings.first()
        val list = db.timetableDao().getAll()
        return list.firstOrNull { it.id == s.activeTableId }?.id ?: list.firstOrNull()?.id
    }

    // ---------- 导入 / 同步 ----------

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
                    detail = buildDetail(normalized),
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
                    val diff = diffDetail(overlapped, normalized)
                    changes += ImportChange(
                        kind = ImportChangeKind.CHANGED,
                        name = normalized.name,
                        detail = diff,
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
                    detail = buildDetail(fromEntity(old)),
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
                    db.courseDao().insert(fromDraft(tableId, d))
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

    private suspend fun sectionsFor(tableId: Long): List<SectionTimeEntity> {
        val mode = db.timeModeDao().getPrimaryMode(tableId) ?: return emptyList()
        return db.timeModeDao().getSections(mode.id)
    }

    /**
     * 把同一门课被拆开的相邻节次并成一条，保留最早那条并延长它的结束节次。
     * 只合并节次首尾相接且课间不超过 30 分钟的记录，午休/晚饭不会误并。
     */
    private suspend fun mergeAdjacentCourses(tableId: Long) {
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

    private fun fromDraft(tableId: Long, d: CourseDraft): CourseEntity = CourseEntity(
        tableId = tableId,
        name = d.name,
        teacher = d.teacher,
        room = d.room,
        dayOfWeek = d.dayOfWeek,
        startSection = d.startSection,
        endSection = d.endSection,
        weekMask = d.weekMask,
        color = if (d.color == 0) assignColor(d.name) else d.color,
        courseType = d.courseType,
        manuallyEdited = false,
        note = d.note,
    )

    private fun fromEntity(e: CourseEntity): CourseDraft = CourseDraft(
        name = e.name,
        teacher = e.teacher,
        room = e.room,
        dayOfWeek = e.dayOfWeek,
        startSection = e.startSection,
        endSection = e.endSection,
        weekMask = e.weekMask,
        color = e.color,
        courseType = e.courseType,
        note = e.note,
    )

    private fun buildDetail(d: CourseDraft): String {
        val parts = mutableListOf<String>()
        parts += TimeUtils.dayName(d.dayOfWeek)
        parts += "第${d.startSection}-${d.endSection}节"
        if (d.room.isNotBlank()) parts += d.room
        parts += WeekMask.toExpression(d.weekMask)
        return parts.joinToString(" · ")
    }

    private fun diffDetail(old: CourseEntity, new: CourseDraft): String {
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
}
