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
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上传队列前台服务(拍板:dataSync 类型 + Room 唯一真相源)。
 *
 * - 并发 worker 可配(设置页 1–4,默认 2;服务启动时读取,改动下次队列启动生效);claim 用 Mutex 串行化
 * - 失败重试次数可配(默认 3,退避 2s/8s/30s 超出封顶);冷却在 worker 内 delay
 * - 仅 Wi-Fi(拍板 2026-10-04):开启后非 Wi-Fi 下 worker 网关轮询暂停,恢复自动续跑(即时生效)
 * - 服务被杀/重启:启动时把 hashing/uploading/cooldown 重置为 pending,从 Room 续跑
 * - 上传成功后对目标目录 list(refresh=true) 触发 OpenList 增量索引(拍板,尽力而为)
 */
@AndroidEntryPoint
class UploadService : Service() {

    @Inject lateinit var repository: UploadRepository

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var logger: com.tomcat927.miscuploader.data.AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val claimMutex = Mutex()
    private val backoffMillis = longArrayOf(2_000L, 8_000L, 30_000L)
    private var running = false
    private var maxRetries = 3
    private var wifiGateLogged = false
    private var pauseLogged = false
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
            serviceScope.launch {
                repository.resetInterrupted()
                maxRetries = settings.loadUploadMaxRetriesOnce()
                observeQueueForNotification()
                repeat(settings.loadUploadConcurrencyOnce()) { serviceScope.launch { workerLoop() } }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun workerLoop() {
        while (serviceScope.isActive) {
            if (!wifiAllowed()) {
                if (!wifiGateLogged) {
                    wifiGateLogged = true
                    logger.log("service", "仅 Wi-Fi 上传：当前非 Wi-Fi，队列暂停")
                }
                delay(15_000L)
                continue
            }
            if (wifiGateLogged) {
                wifiGateLogged = false
                logger.log("service", "Wi-Fi 已恢复，队列续跑")
            }
            // 队列暂停(拍板 2026-10-05):不取新任务,进行中的传完为止;1s 轮询,即时生效
            if (settings.queuePausedFlow.first()) {
                if (!pauseLogged) {
                    pauseLogged = true
                    logger.log("service", "队列已暂停（用户手动）")
                }
                delay(1_000L)
                continue
            }
            if (pauseLogged) {
                pauseLogged = false
                logger.log("service", "队列继续")
            }
            val item = claimMutex.withLock { repository.claimNext() } ?: break
            process(item)
        }
        tryStop()
    }

    /** 仅 Wi-Fi 网关:开关关闭恒放行;开关开启时读实时网络状态(每轮/每 15s 轮询,即时生效) */
    private suspend fun wifiAllowed(): Boolean {
        if (!settings.wifiOnlyFlow.first()) return true
        val manager = getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private suspend fun process(item: UploadItemEntity) {
        val client = connectionManager.clientOrNull()
        if (client == null) {
            // 未连接:退回 pending 停队列;连接后从队列页/启动恢复
            logger.log("upload", "未连接，「${item.displayName}」退回队列")
            repository.requeue(item.id, item.retries)
            tryStop()
            return
        }
        val file = File(item.localPath)
        if (!file.isFile) {
            logger.log("upload", "跳过（本地文件不存在）：${item.localPath}")
            repository.markSkipped(item.id, "本地文件不存在：${item.localPath}")
            return
        }

        // A1 拍板:内容级去重——PUT 前流式 hash,同内容已上传过(任意目录)即跳过并提示已有路径
        val sha = try {
            repository.markHashing(item.id)
            val digest = com.tomcat927.miscuploader.core.ContentHash.sha256(file)
            repository.setSha256(item.id, digest)
            digest
        } catch (e: Exception) {
            handleFailure(item, "读取文件失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        val existing = repository.findHistoryBySha(sha)
        if (existing != null) {
            logger.log("upload", "去重命中：「${item.displayName}」= ${existing.remotePath}")
            repository.markSkipped(item.id, "同内容已存在：${existing.remotePath}")
            return
        }
        repository.markUploading(item.id)

        logger.log("upload", "开始 ${item.displayName}（${file.length()} B → ${item.remotePath}）")
        try {
            // 逐级建目录(best-effort;A2 修正 M3 遗留——文件夹/auto 目录不存在时 PUT 必失败)
            client.mkdirp(item.remotePath)
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
            repository.recordHistory(sha, item.remotePath, item.displayName, file.length())
            logger.log("upload", "完成 ${item.displayName}")
            refreshRemoteDir(item.remoteDir)
        } catch (e: OpenListApiException) {
            handleFailure(item, e.message ?: "请求失败")
        } catch (e: Exception) {
            handleFailure(item, "网络错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun handleFailure(item: UploadItemEntity, message: String) {
        val retries = item.retries + 1
        if (retries <= maxRetries) {
            val wait = backoffMillis[(retries - 1).coerceAtMost(backoffMillis.size - 1)]
            logger.log("upload", "失败 ${item.displayName}：$message（$retries/$maxRetries，${wait / 1000}s 后重试）")
            repository.enterCooldown(item.id, "$message（$retries/$maxRetries 次重试，${wait / 1000}s 后重试）")
            delay(wait)
            repository.requeue(item.id, retries)
        } else {
            logger.log("upload", "最终失败 ${item.displayName}：$message")
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
            combine(repository.items, settings.queuePausedFlow) { items, paused ->
                val inFlight = items.filter { it.state in UploadState.IN_FLIGHT }
                val done = items.count { it.state == UploadState.DONE }
                val active = items.firstOrNull { it.state == UploadState.UPLOADING || it.state == UploadState.HASHING }
                when {
                    paused && active != null -> "暂停中，等当前项完成（${active.displayName}）"
                    paused -> "队列已暂停（待传 ${inFlight.size} 项）"
                    active != null -> {
                        val label = if (active.state == UploadState.HASHING) "校验中" else "上传中"
                        "$label ${active.displayName}（$done 完成，${inFlight.size} 在队列）"
                    }
                    inFlight.isNotEmpty() -> "等待上传 ${inFlight.size} 项（$done 完成）"
                    else -> "上传完成"
                }
            }.collect { notify(it) }
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
