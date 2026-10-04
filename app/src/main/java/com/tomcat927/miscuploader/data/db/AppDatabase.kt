package com.tomcat927.miscuploader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadItemEntity::class, HistoryEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        /**
         * v1→v2(A1 去重+历史):队列表加 sha256 列;新建 upload_history。
         * 手写 Migration 避免破坏性迁移丢在途队列。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_items ADD COLUMN sha256 TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`remotePath` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_history_sha256` ON `upload_history` (`sha256`)")
            }
        }
    }
}
