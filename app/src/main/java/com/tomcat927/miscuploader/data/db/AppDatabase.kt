package com.tomcat927.miscuploader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
}
