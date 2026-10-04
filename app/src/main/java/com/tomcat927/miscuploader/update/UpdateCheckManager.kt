package com.tomcat927.miscuploader.update

import android.util.Log
import com.tomcat927.miscuploader.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动检查更新(拍板 2026-10-04:可开关,默认开,设置「应用更新」卡内切换):
 * - 启动静默检查复用 UpdateService.checkForUpdate(协议/双源/版本比较同手动检查)
 * - 结果进 available:设置卡片无需手动点检查即可显示「发现新版本」
 * - 发现新版本发 foundEvents 一次性事件(Main 页 Snackbar 提示;不上系统通知——
 *   POST_NOTIFICATIONS 运行时请求未接,是已知边界)
 * - 静默检查失败只记日志不打扰;手动「检查更新」在 UpdateViewModel 显式报错
 */
@Singleton
class UpdateCheckManager @Inject constructor(
    private val updateService: UpdateService,
    private val settings: SettingsRepository,
    private val logger: com.tomcat927.miscuploader.data.AppLogger,
) {

    companion object {
        private const val TAG = "UpdateCheck"

        /** 启动后延迟检查,避让自动连接(慢网络下不让更新源请求挤占连接带宽) */
        private const val STARTUP_DELAY_MS = 3_000L
    }

    /** 静默检查发现的可用更新(null = 无/未检查/已最新) */
    private val _available = MutableStateFlow<UpdateService.UpdateInfo?>(null)
    val available: StateFlow<UpdateService.UpdateInfo?> = _available.asStateFlow()

    /** 发现新版本的一次性事件(Main 页 Snackbar;extraBufferCapacity=1,无订阅者时丢弃) */
    val foundEvents = MutableSharedFlow<UpdateService.UpdateInfo>(extraBufferCapacity = 1)

    /** 应用启动调用:开关开启才检查(延迟避让自动连接) */
    suspend fun checkOnStartupIfEnabled() {
        delay(STARTUP_DELAY_MS)
        if (!settings.startupUpdateCheckOnce()) {
            Log.i(TAG, "启动检查更新已关闭,跳过")
            logger.log("update", "启动检查更新已关闭，跳过")
            return
        }
        checkQuietly()
    }

    /** 静默检查(异常吞掉记日志);同版本重复发现不重复发事件 */
    suspend fun checkQuietly() {
        try {
            val info = updateService.checkForUpdate()
            val isNew = info != null && info.versionCode != _available.value?.versionCode
            _available.value = info
            if (info != null && isNew) foundEvents.emit(info)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.javaClass.simpleName}: ${e.message}")
            logger.log("update", "启动/静默检查更新失败：${e.javaClass.simpleName}：${e.message}")
        }
    }
}
