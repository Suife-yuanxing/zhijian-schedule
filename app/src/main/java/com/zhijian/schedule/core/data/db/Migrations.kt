package com.zhijian.schedule.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1→v2：新增「教务成绩」「节假日」两张表 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `grade` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`term` TEXT NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`score` TEXT NOT NULL, `scoreFlag` TEXT NOT NULL, `credit` REAL NOT NULL, " +
                "`hours` INTEGER, `gpa` REAL, `retakeTerm` TEXT NOT NULL, " +
                "`examMode` TEXT NOT NULL, `examNature` TEXT NOT NULL, " +
                "`attr` TEXT NOT NULL, `category` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `holiday` (" +
                "`date` TEXT NOT NULL, `name` TEXT NOT NULL, `isOffDay` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))"
        )
    }
}

/** v2→v3：考试表补「手动编辑」标记（教务同步永不覆盖） */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exam` ADD COLUMN `manuallyEdited` INTEGER NOT NULL DEFAULT 0")
    }
}
