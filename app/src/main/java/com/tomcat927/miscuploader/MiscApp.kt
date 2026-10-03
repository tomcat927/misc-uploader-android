package com.tomcat927.miscuploader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.UploadRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MiscApp : Application() {

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var uploadRepository: UploadRepository

    @Inject lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        ensureUploadChannel()
        // 拍板:配置齐全时启动自动连接(对齐桌面端)
        appScope.launch {
            connectionManager.autoConnectIfConfigured()
        }
        // 队列续跑:有在途任务则拉起前台服务(服务启动时重置 uploading/cooldown → pending)
        uploadRepository.resumeIfPending()
    }

    private fun ensureUploadChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(UPLOAD_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(UPLOAD_CHANNEL, "上传", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val UPLOAD_CHANNEL = "upload"
    }
}
