package com.tomcat927.miscuploader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tomcat927.miscuploader.MainActivity
import com.tomcat927.miscuploader.core.OpenListApiException
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上传队列前台服务(拍板:dataSync 类型 + Room 唯一真相源)。
 *
 * - 并发 worker 2 个(可配置化留 V1.1);claim 用 Mutex 串行化(单进程内足够)
 * - 失败退避 2s/8s/30s 共 3 次重试(沿用桌面端拍板),冷却在 worker 内 delay
 * - 服务被杀/重启:启动时把 uploading/cooldown 重置为 pending,从 Room 续跑
 * - 上传成功后对目标目录 list(refresh=true) 触发 OpenList 增量索引(拍板,尽力而为)
 */
@AndroidEntryPoint
class UploadService : Service() {

    @Inject lateinit var repository: UploadRepository

    @Inject lateinit var connectionManager: ConnectionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val claimMutex = Mutex()
    private val workers = 2
    private val backoffMillis = longArrayOf(2_000L, 8_000L, 30_000L)
    private var running = false
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()

        if (!running) {
            running = true
            serviceScope.launch { repository.resetInterrupted() }
            observeQueueForNotification()
            repeat(workers) { serviceScope.launch { workerLoop() } }
        }
        return START_NOT_STICKY
    }

    private suspend fun workerLoop() {
        while (serviceScope.isActive) {
            val item = claimMutex.withLock { repository.claimNext() } ?: break
            process(item)
        }
        tryStop()
    }

    private suspend fun process(item: UploadItemEntity) {
        val client = connectionManager.clientOrNull()
        if (client == null) {
            // 未连接:退回 pending 停队列;连接后从队列页/启动恢复
            repository.requeue(item.id, item.retries)
            tryStop()
            return
        }
        val file = File(item.localPath)
        if (!file.isFile) {
            repository.markSkipped(item.id, "本地文件不存在：${item.localPath}")
            return
        }
        try {
            var lastPost = 0L
            client.upload(file, item.remotePath, overwrite = true) { sent, total ->
                val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                val now = System.currentTimeMillis()
                if (percent >= 100 || now - lastPost > 400) {
                    lastPost = now
                    repository.postProgress(item.id, percent)
                }
            }
            repository.markDone(item.id)
            refreshRemoteDir(item.remoteDir)
        } catch (e: OpenListApiException) {
            handleFailure(item, e.message ?: "请求失败")
        } catch (e: Exception) {
            handleFailure(item, "网络错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun handleFailure(item: UploadItemEntity, message: String) {
        val retries = item.retries + 1
        if (retries <= backoffMillis.size) {
            val wait = backoffMillis[retries - 1]
            repository.enterCooldown(item.id, "$message（${retries}/${backoffMillis.size} 次重试，${wait / 1000}s 后重试）")
            delay(wait)
            repository.requeue(item.id, retries)
        } else {
            repository.markFailed(item.id, message)
        }
    }

    /** 上传成功 → 刷新目标目录,触发 OpenList 增量索引(尽力而为,失败静默) */
    private fun refreshRemoteDir(remoteDir: String) {
        serviceScope.launch {
            runCatching {
                connectionManager.clientOrNull()?.list(remoteDir, refresh = true)
            }
        }
    }

    private suspend fun tryStop() {
        if (repository.activeCount() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- 通知 ----

    private fun observeQueueForNotification() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            repository.items.collect { items ->
                val inFlight = items.filter { it.state in UploadState.IN_FLIGHT }
                val done = items.count { it.state == UploadState.DONE }
                val uploading = items.firstOrNull { it.state == UploadState.UPLOADING }
                val text = when {
                    uploading != null -> "上传中 ${uploading.displayName}（$done 完成，${inFlight.size} 在队列）"
                    inFlight.isNotEmpty() -> "等待上传 ${inFlight.size} 项（$done 完成）"
                    else -> "上传完成"
                }
                notify(text)
            }
        }
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = baseNotification().setContentText(text).build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startInForeground() {
        val notification = baseNotification()
            .setContentText("准备上传…")
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun baseNotification(): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("杂物上传")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "上传", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        running = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "upload"
        const val NOTIFICATION_ID = 1
    }
}
