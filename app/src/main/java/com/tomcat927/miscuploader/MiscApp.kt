package com.tomcat927.miscuploader

import android.app.Application
import com.tomcat927.miscuploader.data.ConnectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MiscApp : Application() {

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // 拍板:配置齐全时启动自动连接(对齐桌面端)
        appScope.launch {
            connectionManager.autoConnectIfConfigured()
        }
    }
}
