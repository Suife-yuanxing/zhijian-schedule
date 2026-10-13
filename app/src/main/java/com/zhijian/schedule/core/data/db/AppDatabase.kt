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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun timeModeDao(): TimeModeDao
    abstract fun courseDao(): CourseDao
    abstract fun importSnapshotDao(): ImportSnapshotDao
    abstract fun examDao(): ExamDao
    abstract fun courseNoteDao(): CourseNoteDao
}
