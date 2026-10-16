package com.tomcat927.miscuploader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UploadItemEntity::class,
        HistoryEntity::class,
        PendingCleanupEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    abstract fun pendingCleanupDao(): PendingCleanupDao

    abstract fun aiConversationDao(): AiConversationDao

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

        /**
         * v2→v3(AI 归档,拍板 2026-10-15):队列表加 cleanupAfter 列;新建 pending_cleanup(待清理列表)。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_items ADD COLUMN cleanupAfter INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_cleanup` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`localPath` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`remotePath` TEXT NOT NULL, " +
                        "`note` TEXT, " +
                        "`addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_cleanup_localPath` " +
                        "ON `pending_cleanup` (`localPath`)",
                )
            }
        }

        /**
         * v3→v4(AI 助手会话式 agent,拍板 2026-10-16):新建 ai_conversation(多会话) + ai_message(消息)。
         * 手写 Migration,保留队列/历史/待清理。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_conversation` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_message` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`text` TEXT, " +
                        "`toolCallsJson` TEXT, " +
                        "`toolCallId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_message_conversationId` " +
                        "ON `ai_message` (`conversationId`)",
                )
            }
        }
    }
}
