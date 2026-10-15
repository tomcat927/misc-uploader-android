package com.tomcat927.miscuploader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待清理列表(拍板 2026-10-15):AI 归档采纳「进待清理」后,上传完成(done/skipped)的本地原件落此表,
 * 供用户后续手动清理(软删除到回收站)或忽略。
 */
@Entity(
    tableName = "pending_cleanup",
    indices = [Index(value = ["localPath"], unique = true)],
)
data class PendingCleanupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localPath: String,
    val displayName: String,
    val size: Long,
    /** 已归档到的远程完整路径 */
    val remotePath: String,
    /** 备注(如「已在仓库(去重跳过)」) */
    val note: String? = null,
    val addedAt: Long,
)
