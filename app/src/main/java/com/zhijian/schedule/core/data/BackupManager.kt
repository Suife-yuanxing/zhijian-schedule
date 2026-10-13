package com.zhijian.schedule.core.data

import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.db.GradeEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimeModeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 本地 JSON 备份 / 恢复（方案 3.1 同步备份·MVP）。
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
) {

    @Serializable
    data class BackupPayload(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val timetables: List<TimetableEntity> = emptyList(),
        val timeModes: List<TimeModeEntity> = emptyList(),
        val sectionTimes: List<SectionTimeEntity> = emptyList(),
        val courses: List<CourseEntity> = emptyList(),
        val exams: List<ExamEntity> = emptyList(),
        val grades: List<GradeEntity> = emptyList(),
    )

    suspend fun exportJson(): String {
        val timetableDao = db.timetableDao()
        val timetables = timetableDao.getAll()
        val modes = mutableListOf<TimeModeEntity>()
        val sections = mutableListOf<SectionTimeEntity>()
        timetables.forEach { t ->
            val mode = db.timeModeDao().getPrimaryMode(t.id)
            if (mode != null) {
                modes += mode
                sections += db.timeModeDao().getSections(mode.id)
            }
        }
        val courses = db.courseDao().getAll()
        val exams = mutableListOf<ExamEntity>()
        timetables.forEach { t ->
            exams += db.examDao().observeByTable(t.id).first()
        }
        val payload = BackupPayload(
            timetables = timetables,
            timeModes = modes,
            sectionTimes = sections,
            courses = courses,
            exams = exams,
            grades = db.gradeDao().getAll(),
        )
        return json.encodeToString(payload)
    }

    /** 恢复：清空后全量写入（另见方案 4.5 的导入快照，互不影响）。clearAllTables 是阻塞 API，必须离开主线程 */
    suspend fun restoreJson(content: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.decodeFromString<BackupPayload>(content)
            if (payload.timetables.isEmpty()) {
                return@runCatching "备份文件不包含课表数据"
            }
            db.clearAllTables()
            payload.timetables.forEach { db.timetableDao().insert(it) }
            payload.timeModes.forEach { db.timeModeDao().insertMode(it) }
            if (payload.sectionTimes.isNotEmpty()) {
                db.timeModeDao().insertSections(payload.sectionTimes)
            }
            payload.courses.forEach { db.courseDao().insert(it) }
            payload.exams.forEach { db.examDao().insert(it) }
            if (payload.grades.isNotEmpty()) db.gradeDao().insertAll(payload.grades)
            "已恢复 ${payload.timetables.size} 张课表、${payload.courses.size} 门课程"
        }
    }
}
