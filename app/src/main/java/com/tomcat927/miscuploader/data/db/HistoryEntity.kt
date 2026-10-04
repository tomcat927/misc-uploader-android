package com.tomcat927.miscuploader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 上传历史(A1 拍板,语义对齐桌面端):内容级 SHA-256 → 最新落点。
 * 同 sha 重复上传会 REPLACE(更新最新路径与时间);上限一万条由 repository 修剪。
 */
@Entity(
    tableName = "upload_history",
    indices = [Index("sha256")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sha256: String,
    /** 完整远程路径(含文件名) */
    val remotePath: String,
    val displayName: String,
    val size: Long,
    val uploadedAt: Long,
) {
    companion object {
        /** 历史上限(拍板对齐桌面端:一万条) */
        const val MAX_ENTRIES = 10_000
    }
}
