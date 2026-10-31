package com.zhijian.schedule.core.repository

import androidx.room.withTransaction
import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimeModeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.JwMeta
import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.jw.SectionTimes
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课表数据的唯一入口：对外暴露响应式状态、课程/考试 CRUD 与当前课表解析。
 *
 * 导入与教务同步的流水线（预览 Diff、落库、快照、连堂课归并）实现见
 * [TimetableImportService]；本类只做转发，保持外部调用面不变。
 */
@Singleton
class ScheduleRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settingsStore: SettingsStore,
        json: Json,
    ) {
        private val importer = TimetableImportService(db, settingsStore, json)

        val settings: Flow<AppSettings> = settingsStore.settings

        val timetables: Flow<List<TimetableEntity>> = db.timetableDao().observeAll()

        val activeTimetable: Flow<TimetableEntity?> =
            combine(settings, timetables) { s, list ->
                list.firstOrNull { it.id == s.activeTableId } ?: list.firstOrNull()
            }

        val activeCourses: Flow<List<CourseEntity>> =
            activeTimetable.flatMapLatest { t ->
                if (t == null) flowOf(emptyList()) else db.courseDao().observeByTable(t.id)
            }

        val activeMode: Flow<TimeModeEntity?> =
            activeTimetable.flatMapLatest { t ->
                if (t == null) {
                    flowOf(null)
                } else {
                    db
                        .timeModeDao()
                        .observeModes(t.id)
                        .map { modes -> modes.firstOrNull() }
                }
            }

        val activeSections: Flow<List<SectionTimeEntity>> =
            activeMode.flatMapLatest { mode ->
                if (mode == null) flowOf(emptyList()) else db.timeModeDao().observeSections(mode.id)
            }

        val activeExams =
            activeTimetable.flatMapLatest { t ->
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
            db.timetableDao().getAll().forEach { importer.mergeAdjacentCourses(it.id) }
        }

        suspend fun createTimetable(
            name: String,
            semester: String,
            startDate: LocalDate,
            totalWeeks: Int,
        ): Long {
            val dao = db.timetableDao()
            val tableId =
                dao.insert(
                    TimetableEntity(
                        name = name,
                        semester = semester,
                        startDate = TimeUtils.formatDate(startDate),
                        totalWeeks = totalWeeks,
                    ),
                )
            val modeId =
                db.timeModeDao().insertMode(
                    TimeModeEntity(tableId = tableId, weekRange = "1-$totalWeeks"),
                )
            db.timeModeDao().insertSections(defaultSections(modeId))
            return tableId
        }

        // ---------- 课程 CRUD ----------

        suspend fun saveCourse(
            draft: CourseDraft,
            courseId: Long? = null,
            manuallyEdited: Boolean = true,
        ): Long {
            val tableId = currentTableId() ?: return -1
            return if (courseId == null) {
                db.courseDao().insert(
                    draft.toEntity(
                        tableId = tableId,
                        color = if (draft.color == 0) defaultCourseColor(draft.name) else draft.color,
                        manuallyEdited = manuallyEdited,
                    ),
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
                    ),
                )
                courseId
            }
        }

        suspend fun deleteCourse(id: Long) = db.courseDao().deleteById(id)

        suspend fun getCourse(id: Long): CourseEntity? = db.courseDao().getById(id)

        suspend fun updateTimetable(timetable: TimetableEntity) = db.timetableDao().update(timetable)

        suspend fun updateSectionTime(section: SectionTimeEntity) = db.timeModeDao().updateSection(section)

        /**
         * 用来源（教务页面 / 截图 / 日历）带回来的上课时间重建当前课表的作息。
         *
         * 作息不再硬编码：抓到什么时间就用什么时间。页面没给的节次保留本地值，
         * 课程用到但两处都没有的节次按上一个小节顺推补齐。
         *
         * @param incoming 来源带的作息；为空表示来源没有时间信息，直接返回 false
         * @param drafts 即将落库的课程，决定至少要多少个小节
         * @return true 表示作息被改写
         */
        suspend fun applySectionTimes(
            incoming: List<SectionTimeDraft>,
            drafts: List<CourseDraft> = emptyList(),
        ): Boolean {
            if (incoming.isEmpty()) return false
            val tableId = currentTableId() ?: return false
            val mode = db.timeModeDao().getPrimaryMode(tableId) ?: return false
            val current = db.timeModeDao().getSections(mode.id)
            val neededEnd = drafts.maxOfOrNull { it.endSection } ?: 0
            val plan = SectionTimePlan.plan(current, incoming, neededEnd) ?: return false
            if (plan.map { it.startTime to it.endTime } == current.map { it.startTime to it.endTime }) {
                return false
            }
            db.withTransaction {
                val existing = current.associateBy { it.sectionNo }
                plan.forEach { section ->
                    if (existing[section.sectionNo] != null) {
                        db.timeModeDao().updateSection(section)
                    } else {
                        db.timeModeDao().insertSections(listOf(section))
                    }
                }
            }
            return true
        }

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
                        tableId = tableId,
                        name = name,
                        room = room,
                        seat = seat,
                        examTime = examTime,
                        note = note,
                        manuallyEdited = true,
                    ),
                )
            } else {
                val old = db.examDao().getById(examId) ?: return -1
                db.examDao().update(
                    old.copy(
                        name = name,
                        room = room,
                        seat = seat,
                        examTime = examTime,
                        note = note,
                        manuallyEdited = true,
                    ),
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

        /** 课名 -> 色板取色（同名课程颜色稳定）。 */
        fun assignColor(name: String): Int = defaultCourseColor(name)

        /** 当前生效课表 id。 */
        suspend fun currentTableId(): Long? = importer.currentTableId()

        // ---------- 导入 / 同步 ----------

        /** 与本地数据 Diff 生成导入预览；实现见 [TimetableImportService.buildPreview]。 */
        suspend fun buildPreview(
            drafts: List<CourseDraft>,
            source: ImportSource,
            syncMode: Boolean = false,
            sectionTimes: List<SectionTimeDraft> = emptyList(),
            meta: JwMeta? = null,
        ): ImportPreview = importer.buildPreview(drafts, source, syncMode, sectionTimes, meta)

        /** 应用导入结果；实现见 [TimetableImportService.applyImport]。 */
        suspend fun applyImport(
            preview: ImportPreview,
            applyConflicts: Boolean = false,
        ) = importer.applyImport(preview, applyConflicts)

        /** 保存导入快照；实现见 [TimetableImportService.saveSnapshot]。 */
        suspend fun saveSnapshot(
            source: ImportSource,
            drafts: List<CourseDraft>,
        ) = importer.saveSnapshot(source, drafts)

        companion object {
            /**
             * 首次建表时的初始作息。数字来自 [SectionTimes.defaults]，
             * 与截图识别、教务抓取共用同一套拆分规则，这里不再另抄一份时间表。
             */
            fun defaultSections(modeId: Long): List<SectionTimeEntity> =
                SectionTimes.defaults().map { draft ->
                    val period = (draft.sectionNo - 1) / SectionTimes.SECTIONS_PER_PERIOD + 1
                    SectionTimeEntity(
                        modeId = modeId,
                        sectionNo = draft.sectionNo,
                        periodNo = period,
                        session = if (period <= 2) "AM" else "PM",
                        startTime = draft.startTime,
                        endTime = draft.endTime,
                    )
                }
        }
    }
