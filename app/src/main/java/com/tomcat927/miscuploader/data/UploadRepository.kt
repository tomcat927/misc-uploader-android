package com.tomcat927.miscuploader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.tomcat927.miscuploader.data.db.UploadDao
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import com.tomcat927.miscuploader.service.UploadService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上传队列仓库:Room 唯一真相源(拍板——UI 与前台服务靠同一张表对齐,
 * 进程被杀/服务重启/续跑都不需要额外协议)。
 */
@Singleton
class UploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: UploadDao,
    private val appScope: CoroutineScope,
    private val logger: AppLogger,
    private val settings: SettingsRepository,
) {

    val items: Flow<List<UploadItemEntity>> = dao.observeAll()

    /**
     * 入队并拉起前台服务(拍板 A2:目标目录在入队侧规划——手动模式 = 所选目录,
     * 自动模式 = 按各文件 mtime 的 auto/yyyy/MM;文件夹结构由调用方展开保留)。
     */
    fun enqueue(tasks: List<UploadTask>) {
        if (tasks.isEmpty()) return
        appScope.launch {
            val now = System.currentTimeMillis()
            val items = tasks.mapNotNull { t ->
                if (!t.file.isFile) return@mapNotNull null
                UploadItemEntity(
                    localPath = t.file.absolutePath,
                    displayName = t.file.name,
                    size = t.file.length(),
                    remoteDir = t.remoteDir,
                    remotePath = UploadPlanning.joinRemotePath(t.remoteDir, t.rel),
                    state = UploadState.PENDING,
                    progress = 0,
                    enqueuedAt = now,
                )
            }
            if (items.isEmpty()) return@launch
            dao.insertAll(items)
            val dirs = tasks.map { it.remoteDir }.distinct()
            val dirDesc = if (dirs.size <= 3) dirs.joinToString() else "${dirs.take(3).joinToString()} 等 ${dirs.size} 个目录"
            logger.log("queue", "入队 ${items.size} 项 → $dirDesc")
            startService()
        }
    }

    /**
     * 分享接收(M4 拍板):content:// 先拷贝到 app cache 再按文件入队(队列/重试逻辑不变);
     * 目标由上传模式决定——自动模式按分享时刻归 auto/yyyy/MM,手动模式为仓库根目录 "/"。
     * 上传完成后由启动清理回收孤儿缓存。
     * @return 实际入队数与目标描述
     */
    suspend fun enqueueFromShare(uris: List<Uri>): ShareEnqueue = withContext(Dispatchers.IO) {
        val mode = settings.loadUploadModeOnce()
        val target = if (mode == UploadMode.AUTO_DATE) {
            UploadPlanning.autoDirFor(System.currentTimeMillis())
        } else {
            "/"
        }
        val now = System.currentTimeMillis()
        val items = uris.mapNotNull { uri ->
            runCatching {
                val name = resolveDisplayName(uri)
                val cacheDir = File(context.cacheDir, "share").apply { mkdirs() }
                val dest = File(cacheDir, "${System.nanoTime()}_${name.replace(Regex("[/\\\\]"), "_")}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@mapNotNull null
                if (!dest.isFile || dest.length() == 0L) {
                    dest.delete()
                    return@mapNotNull null
                }
                logger.log("share", "接收分享：$name（${dest.length()} B）")
                UploadItemEntity(
                    localPath = dest.absolutePath,
                    displayName = name,
                    size = dest.length(),
                    remoteDir = target,
                    remotePath = UploadPlanning.joinRemotePath(target, name),
                    state = UploadState.PENDING,
                    progress = 0,
                    enqueuedAt = now,
                )
            }.getOrNull()
        }
        if (items.isNotEmpty()) {
            dao.insertAll(items)
            logger.log("queue", "分享入队 ${items.size} 项 → $target")
            startService()
        }
        ShareEnqueue(items.size, target)
    }

    /** 应用启动/连接成功时恢复:清理孤儿缓存;有在途任务则拉起服务(服务启动重置中断状态) */
    fun resumeIfPending() {
        appScope.launch {
            cleanupOrphanShareCache()
            if (dao.activeCount() > 0) {
                logger.log("queue", "恢复队列：${dao.activeCount()} 项在途")
                startService()
            }
        }
    }

    fun retry(id: Long) {
        appScope.launch {
            logger.log("queue", "手动重试 #$id")
            dao.retry(id)
            startService()
        }
    }

    fun retryAllFailed() {
        appScope.launch {
            val n = dao.retryAllFailed()
            logger.log("queue", "重试全部失败：$n 项")
            if (n > 0) startService()
        }
    }

    fun clearFinished() {
        appScope.launch { dao.clearFinished() }
    }

    // ---- 服务内部使用 ----

    suspend fun claimNext(): UploadItemEntity? {
        val item = dao.firstPending() ?: return null
        dao.markUploading(item.id, System.currentTimeMillis())
        return item.copy(state = UploadState.UPLOADING)
    }

    suspend fun resetInterrupted() {
        dao.resetInterrupted()
        logger.log("service", "清理上次中断的上传中/冷却任务 → pending")
    }

    /** 进度节流由服务侧控制;此处异步落库,不阻塞 OkHttp 写线程 */
    fun postProgress(id: Long, percent: Int) {
        appScope.launch { dao.updateProgress(id, percent) }
    }

    suspend fun markDone(id: Long) = dao.markDone(id, System.currentTimeMillis())

    suspend fun markSkipped(id: Long, error: String) =
        dao.markSkipped(id, error, System.currentTimeMillis())

    suspend fun markFailed(id: Long, error: String) =
        dao.markFailed(id, error, System.currentTimeMillis())

    suspend fun enterCooldown(id: Long, error: String) = dao.markCooldown(id, error)

    suspend fun requeue(id: Long, retries: Int) = dao.requeue(id, retries)

    suspend fun activeCount(): Int = dao.activeCount()

    private suspend fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return uri.lastPathSegment ?: "未命名"
    }

    /** 删除不在在途队列中的分享缓存文件(启动时调用) */
    private suspend fun cleanupOrphanShareCache() = withContext(Dispatchers.IO) {
        val keep = dao.inFlight().mapTo(mutableSetOf()) { it.localPath }
        File(context.cacheDir, "share").listFiles()?.forEach { f ->
            if (f.absolutePath !in keep) f.delete()
        }
    }

    private fun startService() {
        context.startForegroundService(Intent(context, UploadService::class.java))
    }
}

/** 单个上传任务:目标目录在入队侧规划(A2) */
data class UploadTask(
    val file: File,
    val remoteDir: String,
    val rel: String,
)

/** 分享接收结果 */
data class ShareEnqueue(val count: Int, val target: String)
