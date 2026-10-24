package com.zhijian.schedule.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "timetable")
@Serializable
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val semester: String = "",
    /** 第 1 周周一的日期 ISO "2026-09-01" */
    val startDate: String,
    val totalWeeks: Int = 20,
    val type: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "time_mode",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tableId")],
)
@Serializable
data class TimeModeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    val name: String = "默认节次模式",
    /** 每日大节数 */
    val periodsPerDay: Int = 6,
    /** 每大节包含小節数 */
    val sectionsPerPeriod: Int = 2,
    /** 分场切点："2" 表示第 2 大节之后进入下午场 */
    val sessionSplit: String = "2",
    /** 生效周次区间，如 "1-20" */
    val weekRange: String = "1-20",
)

@Entity(
    tableName = "section_time",
    foreignKeys = [
        ForeignKey(
            entity = TimeModeEntity::class,
            parentColumns = ["id"],
            childColumns = ["modeId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("modeId")],
)
@Serializable
data class SectionTimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modeId: Long,
    /** 小节序号 1..12（存储原子单位） */
    val sectionNo: Int,
    /** 大节序号 1..6 */
    val periodNo: Int,
    /** "AM" / "PM" */
    val session: String,
    val startTime: String,
    val endTime: String,
)

@Entity(
    tableName = "course",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tableId")],
)
@Serializable
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    /** 1=周一 .. 7=周日 */
    val dayOfWeek: Int,
    /** 小节区间，1..12 */
    val startSection: Int,
    val endSection: Int,
    /** 位掩码，长度 = 学期总周数 */
    val weekMask: String,
    /** ARGB 色值 */
    val color: Int,
    val courseType: Int = 0,
    /** 用户手动编辑过，自动同步时永不覆盖 */
    val manuallyEdited: Boolean = false,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "import_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tableId")],
)
data class ImportSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    /** 标准 JSON 课程列表 */
    val payload: String,
    val source: String,
    val applied: Boolean = true,
    val fetchTime: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "exam",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tableId")],
)
@Serializable
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    val name: String,
    val room: String = "",
    val seat: String = "",
    /** 考试开始时间 epoch millis */
    val examTime: Long,
    val note: String = "",
    /** 用户手动创建/编辑过的考试，教务同步永不覆盖（与 CourseEntity 同语义） */
    val manuallyEdited: Boolean = false,
)

@Entity(
    tableName = "course_note",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("courseId")],
)
data class CourseNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val content: String,
    val updateTime: Long = System.currentTimeMillis(),
)

/** 教务成绩（强智 jsxsd 成绩查询，全量缓存） */
@Entity(tableName = "grade")
@Serializable
data class GradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 开课学期，如 2025-2026-1 */
    val term: String,
    val code: String,
    val name: String,
    /** 原文成绩："85" / "A" / "优秀" */
    val score: String,
    /** 成绩标识（补考/重修标记，如 "*"） */
    val scoreFlag: String = "",
    val credit: Double,
    val hours: Int? = null,
    /** 教务给的学分绩点 */
    val gpa: Double? = null,
    /** 补重学期；非空表示该行是补考/重修记录 */
    val retakeTerm: String = "",
    val examMode: String = "",
    val examNature: String = "",
    val attr: String = "",
    val category: String = "",
)

/** 节假日/调休（holiday-cn / timor.tech 数据源，按年缓存） */
@Entity(tableName = "holiday")
data class HolidayEntity(
    /** yyyy-MM-dd */
    @PrimaryKey val date: String,
    val name: String,
    /** true=法定放假；false=调休补班 */
    val isOffDay: Boolean,
)
