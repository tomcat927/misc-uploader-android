package com.tomcat927.miscuploader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {

    @Insert
    suspend fun insertAll(items: List<UploadItemEntity>)

    @Query("SELECT * FROM upload_items ORDER BY enqueuedAt DESC")
    fun observeAll(): Flow<List<UploadItemEntity>>

    @Query("SELECT * FROM upload_items WHERE state = 'pending' ORDER BY id LIMIT 1")
    suspend fun firstPending(): UploadItemEntity?

    @Query("UPDATE upload_items SET state = 'uploading', startedAt = :now, error = NULL WHERE id = :id")
    suspend fun markUploading(id: Long, now: Long)

    @Query("UPDATE upload_items SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int)

    @Query("UPDATE upload_items SET state = 'done', progress = 100, finishedAt = :now, error = NULL WHERE id = :id")
    suspend fun markDone(id: Long, now: Long)

    @Query("UPDATE upload_items SET state = 'skipped', finishedAt = :now, error = :error WHERE id = :id")
    suspend fun markSkipped(id: Long, error: String, now: Long)

    @Query("UPDATE upload_items SET state = 'failed', finishedAt = :now, error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, now: Long)

    @Query("UPDATE upload_items SET state = 'cooldown', error = :error WHERE id = :id")
    suspend fun markCooldown(id: Long, error: String)

    @Query("UPDATE upload_items SET state = 'pending', retries = :retries, error = NULL WHERE id = :id")
    suspend fun requeue(id: Long, retries: Int)

    @Query("UPDATE upload_items SET state = 'pending', error = NULL WHERE state = 'cooldown' OR state = 'uploading'")
    suspend fun resetInterrupted()

    @Query("UPDATE upload_items SET state = 'pending', retries = 0, progress = 0, error = NULL, finishedAt = NULL WHERE id = :id AND state = 'failed'")
    suspend fun retry(id: Long)

    @Query("UPDATE upload_items SET state = 'pending', retries = 0, progress = 0, error = NULL, finishedAt = NULL WHERE state = 'failed'")
    suspend fun retryAllFailed(): Int

    @Query("DELETE FROM upload_items WHERE state IN ('done','failed','skipped')")
    suspend fun clearFinished()

    @Query("SELECT COUNT(*) FROM upload_items WHERE state IN ('pending','uploading','cooldown')")
    suspend fun activeCount(): Int
}
