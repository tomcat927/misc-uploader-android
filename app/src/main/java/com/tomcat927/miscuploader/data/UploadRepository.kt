package com.tomcat927.miscuploader.data

import android.content.Context
import android.content.Intent
import com.tomcat927.miscuploader.data.db.UploadDao
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import com.tomcat927.miscuploader.service.UploadService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 上传队列仓库:Room 唯一真相源(拍板——UI 与前台服务靠同一张表对齐,
 * 进程被杀/服务重启/续跑都不需要额外协议)。
 */
@Singleton
class UploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: UploadDao,
    private val appScope: CoroutineScope,
) {

    val items: Flow<List<UploadItemEntity>> = dao.observeAll()

    /**
     * 入队并拉起前台服务。
     * @param files (本地文件, 相对 remoteDir 的相对路径) 对;文件夹结构在入队侧递归展开(拍板沿用桌面端)
     */
    fun enqueue(files: List<Pair<File, String>>, remoteDir: String) {
        if (files.isEmpty()) return
        appScope.launch {
            val now = System.currentTimeMillis()
            val items = files.mapNotNull { (file, rel) ->
                if (!file.isFile) return@mapNotNull null
                UploadItemEntity(
                    localPath = file.absolutePath,
                    displayName = file.name,
                    size = file.length(),
                    remoteDir = remoteDir,
                    remotePath = if (remoteDir == "/") "/$rel" else "$remoteDir/$rel",
                    state = UploadState.PENDING,
                    progress = 0,
                    enqueuedAt = now,
                )
            }
            if (items.isEmpty()) return@launch
            dao.insertAll(items)
            startService()
        }
    }

    /** 应用启动时恢复:有在途任务则拉起服务继续(服务启动时会重置 uploading/cooldown → pending) */
    fun resumeIfPending() {
        appScope.launch {
            if (dao.activeCount() > 0) startService()
        }
    }

    fun retry(id: Long) {
        appScope.launch {
            dao.retry(id)
            startService()
        }
    }

    fun retryAllFailed() {
        appScope.launch {
            dao.retryAllFailed()
            startService()
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

    suspend fun resetInterrupted() = dao.resetInterrupted()

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

    suspend fun all(): List<UploadItemEntity> = dao.observeAll().first()

    private fun startService() {
        context.startForegroundService(Intent(context, UploadService::class.java))
    }
}
