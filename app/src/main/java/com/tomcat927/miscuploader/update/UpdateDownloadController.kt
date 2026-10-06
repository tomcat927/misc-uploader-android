package com.tomcat927.miscuploader.update

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 更新包下载状态(前台服务唯一写方,VM/设置卡读) */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class Downloading(val tagName: String, val progress: Float) : UpdateDownloadState

    data class ReadyToInstall(val file: File, val tagName: String) : UpdateDownloadState

    data class Failed(val message: String) : UpdateDownloadState
}

/**
 * 热更新下载桥(拍板 2026-10-06,下载前台服务化):VM 拉起服务前写入任务,服务执行中更新状态;
 * 状态流同时驱动设置卡与通知栏进度。单例 = 服务与 VM 同进程共享,无需跨进程协议。
 */
@Singleton
class UpdateDownloadController @Inject constructor() {

    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    /** 待执行任务(VM 在拉起服务前写入;服务 onStartCommand 读取) */
    @Volatile
    var pendingInfo: UpdateService.UpdateInfo? = null

    fun prepare(info: UpdateService.UpdateInfo) {
        pendingInfo = info
        _state.value = UpdateDownloadState.Downloading(info.tagName, 0f)
    }

    fun updateProgress(tagName: String, progress: Float) {
        _state.value = UpdateDownloadState.Downloading(tagName, progress)
    }

    fun markReady(file: File, tagName: String) {
        _state.value = UpdateDownloadState.ReadyToInstall(file, tagName)
    }

    fun markFailed(message: String) {
        _state.value = UpdateDownloadState.Failed(message)
    }
}
