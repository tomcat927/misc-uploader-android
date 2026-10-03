package com.tomcat927.miscuploader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 队列状态机沿用桌面端拍板;hashing 状态留给 V1.1 去重,M3 不使用 */
object UploadState {
    const val PENDING = "pending"
    const val UPLOADING = "uploading"
    const val COOLDOWN = "cooldown"
    const val DONE = "done"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"

    val IN_FLIGHT = listOf(PENDING, UPLOADING, COOLDOWN)
    val FINISHED = listOf(DONE, FAILED, SKIPPED)
}

@Entity(tableName = "upload_items")
data class UploadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localPath: String,
    val displayName: String,
    val size: Long,
    /** 目标目录(远程相对路径,"/" = 根) */
    val remoteDir: String,
    /** 完整远程路径(含文件名) */
    val remotePath: String,
    val state: String,
    /** 0-100 */
    val progress: Int,
    val retries: Int = 0,
    val error: String? = null,
    val enqueuedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)
