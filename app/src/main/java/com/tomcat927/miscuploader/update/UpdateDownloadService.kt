package com.tomcat927.miscuploader.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tomcat927.miscuploader.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 更新包下载前台服务(拍板 2026-10-06):通知栏进度 + 后台存活(与上传服务同 dataSync 架构)。
 * 下载 + SHA-256 校验完成后尝试自动拉起安装器(对齐"一键到底"拍板);app 在后台时
 * startActivity 受系统限制可能被静默拦截,完成通知的"点击安装"(PendingIntent 豁免)是可靠兜底。
 * START_NOT_STICKY:进程被杀 = 下载作废重下(前台服务已大幅降低该概率)。
 */
@AndroidEntryPoint
class UpdateDownloadService : Service() {

    @Inject lateinit var updateService: UpdateService

    @Inject lateinit var controller: UpdateDownloadController

    @Inject lateinit var logger: com.tomcat927.miscuploader.data.AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val info = controller.pendingInfo
        if (info == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastNotifiedPercent = -1 // 同实例重复下载(失败重试)时进度通知从头刷新
        startInForeground(info.tagName)
        if (downloadJob?.isActive != true) {
            downloadJob = serviceScope.launch { runDownload(info) }
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(info: UpdateService.UpdateInfo) {
        logger.log("update", "前台服务开始下载 ${info.tagName}")
        try {
            val file = updateService.downloadApk(info) { progress ->
                // 进度按 1% 节流:状态流与通知都避免高频重绘
                val percent = (progress * 100).toInt()
                if (percent != lastNotifiedPercent) {
                    lastNotifiedPercent = percent
                    controller.updateProgress(info.tagName, progress)
                    notifyProgress(info.tagName, percent)
                }
            }
            controller.markReady(file, info.tagName)
            logger.log("update", "下载完成：${file.absolutePath}")
            notifyDone(file, info.tagName)
            tryInstall(file)
        } catch (e: Exception) {
            logger.log("update", "下载失败：${e.message ?: e.javaClass.simpleName}")
            controller.markFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** 前台时直接弹安装器;后台被系统拦截属静默失败,由完成通知的"点击安装"兜底 */
    private fun tryInstall(file: File) {
        try {
            if (packageManager.canRequestPackageInstalls()) {
                startActivity(updateService.createInstallIntent(file))
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (e: Exception) {
            logger.log("update", "拉起安装器失败（可用完成通知或设置页继续）：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- 通知 ----

    private fun startInForeground(tagName: String) {
        val notification = baseNotification()
            .setContentTitle("正在下载更新 $tagName")
            .setContentText("准备下载…")
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

    private fun notifyProgress(tagName: String, percent: Int) {
        val notification = baseNotification()
            .setContentTitle("正在下载更新 $tagName")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** 完成通知带"点击安装"(PendingIntent 免后台弹窗限制),自动取消不占通知栏 */
    private fun notifyDone(file: File, tagName: String) {
        val installPi = PendingIntent.getActivity(
            this,
            1,
            updateService.createInstallIntent(file),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = baseNotification()
            .setContentTitle("更新包下载完成")
            .setContentText("点击安装 $tagName")
            .setContentIntent(installPi)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun baseNotification(): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("更新下载")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "更新下载", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "update_download"
        private const val NOTIFICATION_ID = 2001
    }
}
