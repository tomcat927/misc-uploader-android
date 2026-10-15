package com.tomcat927.miscuploader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingCleanupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PendingCleanupEntity>)

    @Query("SELECT * FROM pending_cleanup ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<PendingCleanupEntity>>

    @Query("SELECT COUNT(*) FROM pending_cleanup")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_cleanup WHERE localPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM pending_cleanup")
    suspend fun clear()
}
