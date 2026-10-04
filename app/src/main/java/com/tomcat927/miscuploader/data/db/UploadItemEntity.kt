package com.tomcat927.miscuploader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 队列状态机沿用桌面端拍板;hashing 状态 A1 启用(PUT 前流式 hash 去重) */
object UploadState {
    const val PENDING = "pending"
    const val HASHING = "hashing"
    const val UPLOADING = "uploading"
    const val COOLDOWN = "cooldown"
    const val DONE = "done"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"

    val IN_FLIGHT = listOf(PENDING, HASHING, UPLOADING, COOLDOWN)
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
    /** 内容 SHA-256(A1:worker 在 PUT 前计算,重试轮复用判断) */
    val sha256: String? = null,
)
