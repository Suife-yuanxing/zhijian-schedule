package com.zhijian.schedule.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetable ORDER BY createdAt ASC")
    suspend fun getAll(): List<TimetableEntity>

    @Query("SELECT * FROM timetable WHERE id = :id")
    suspend fun getById(id: Long): TimetableEntity?

    @Query("SELECT * FROM timetable WHERE id = :id")
    fun observeById(id: Long): Flow<TimetableEntity?>

    @Insert
    suspend fun insert(item: TimetableEntity): Long

    @Update
    suspend fun update(item: TimetableEntity)

    @Delete
    suspend fun delete(item: TimetableEntity)
}

@Dao
interface TimeModeDao {
    @Query("SELECT * FROM time_mode WHERE tableId = :tableId ORDER BY id ASC")
    fun observeModes(tableId: Long): Flow<List<TimeModeEntity>>

    @Query("SELECT * FROM time_mode WHERE tableId = :tableId ORDER BY id ASC LIMIT 1")
    suspend fun getPrimaryMode(tableId: Long): TimeModeEntity?

    @Insert
    suspend fun insertMode(item: TimeModeEntity): Long

    @Update
    suspend fun updateMode(item: TimeModeEntity)

    @Query("SELECT * FROM section_time WHERE modeId = :modeId ORDER BY sectionNo ASC")
    fun observeSections(modeId: Long): Flow<List<SectionTimeEntity>>

    @Query("SELECT * FROM section_time WHERE modeId = :modeId ORDER BY sectionNo ASC")
    suspend fun getSections(modeId: Long): List<SectionTimeEntity>

    @Insert
    suspend fun insertSections(items: List<SectionTimeEntity>)

    @Update
    suspend fun updateSection(item: SectionTimeEntity)

    @Query("DELETE FROM section_time WHERE modeId = :modeId")
    suspend fun deleteSections(modeId: Long)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM course WHERE tableId = :tableId ORDER BY dayOfWeek ASC, startSection ASC")
    fun observeByTable(tableId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE tableId = :tableId ORDER BY dayOfWeek ASC, startSection ASC")
    suspend fun getByTable(tableId: Long): List<CourseEntity>

    @Query("SELECT * FROM course")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Query("SELECT * FROM course WHERE tableId = :tableId AND dayOfWeek = :day AND startSection = :startSection AND name = :name LIMIT 1")
    suspend fun findMatch(tableId: Long, day: Int, startSection: Int, name: String): CourseEntity?

    @Insert
    suspend fun insert(item: CourseEntity): Long

    @Insert
    suspend fun insertAll(items: List<CourseEntity>): List<Long>

    @Update
    suspend fun update(item: CourseEntity)

    @Upsert
    suspend fun upsert(item: CourseEntity): Long

    @Delete
    suspend fun delete(item: CourseEntity)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM course WHERE tableId = :tableId")
    suspend fun deleteByTable(tableId: Long)
}

@Dao
interface ImportSnapshotDao {
    @Query("SELECT * FROM import_snapshot WHERE tableId = :tableId ORDER BY fetchTime DESC LIMIT :limit")
    fun observeLatest(tableId: Long, limit: Int = 10): Flow<List<ImportSnapshotEntity>>

    @Query("SELECT * FROM import_snapshot WHERE tableId = :tableId ORDER BY fetchTime DESC LIMIT 1")
    suspend fun getLatest(tableId: Long): ImportSnapshotEntity?

    @Insert
    suspend fun insert(item: ImportSnapshotEntity): Long

    @Query("SELECT * FROM import_snapshot WHERE tableId = :tableId ORDER BY fetchTime DESC LIMIT :keep")
    suspend fun keepLatest(tableId: Long, keep: Int): List<ImportSnapshotEntity>

    @Query("DELETE FROM import_snapshot WHERE id NOT IN (:keepIds) AND tableId = :tableId")
    suspend fun deleteExcept(tableId: Long, keepIds: List<Long>)
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exam WHERE tableId = :tableId ORDER BY examTime ASC")
    fun observeByTable(tableId: Long): Flow<List<ExamEntity>>

    @Insert
    suspend fun insert(item: ExamEntity): Long

    @Update
    suspend fun update(item: ExamEntity)

    @Delete
    suspend fun delete(item: ExamEntity)
}

@Dao
interface CourseNoteDao {
    @Query("SELECT * FROM course_note WHERE courseId = :courseId ORDER BY updateTime DESC")
    fun observeByCourse(courseId: Long): Flow<List<CourseNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CourseNoteEntity): Long

    @Delete
    suspend fun delete(item: CourseNoteEntity)
}
