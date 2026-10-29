package com.zhijian.schedule.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TimetableEntity::class,
        TimeModeEntity::class,
        SectionTimeEntity::class,
        CourseEntity::class,
        ImportSnapshotEntity::class,
        ExamEntity::class,
        CourseNoteEntity::class,
        GradeEntity::class,
        HolidayEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao

    abstract fun timeModeDao(): TimeModeDao

    abstract fun courseDao(): CourseDao

    abstract fun importSnapshotDao(): ImportSnapshotDao

    abstract fun examDao(): ExamDao

    abstract fun courseNoteDao(): CourseNoteDao

    abstract fun gradeDao(): GradeDao

    abstract fun holidayDao(): HolidayDao
}
