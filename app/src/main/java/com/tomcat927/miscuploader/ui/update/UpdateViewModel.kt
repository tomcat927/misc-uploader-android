package com.tomcat927.miscuploader.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.UpdateSourcePreference
import com.tomcat927.miscuploader.update.UpdateCheckManager
import com.tomcat927.miscuploader.update.UpdateDownloadController
import com.tomcat927.miscuploader.update.UpdateDownloadService
import com.tomcat927.miscuploader.update.UpdateDownloadState
import com.tomcat927.miscuploader.update.UpdateService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateService.UpdateInfo) : UpdateState
    data object NoUpdate : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File, val tagName: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateService: UpdateService,
    private val updateCheckManager: UpdateCheckManager,
    private val downloadController: UpdateDownloadController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 发现新版本的确认弹窗(主动检查/启动发现共用,Main 页渲染) */
    private val _showInstallConfirm = MutableStateFlow(false)
    val showInstallConfirm: StateFlow<Boolean> = _showInstallConfirm.asStateFlow()

    /** 当前弹窗来源:true=启动发现(「暂不」记住该版本);false=主动检查(每次都弹) */
    private var confirmFromStartup = false

    /** 启动弹窗被「暂不」的版本 tag(空 = 无) */
    private var dismissedTag = ""

    /** 静默提示事件:已「暂不」过的版本再次发现时 Snackbar(不弹窗) */
    val snackEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 启动检查更新开关(设置「应用更新」卡内切换) */
    val startupCheckEnabled: StateFlow<Boolean> = settings.startupUpdateCheckFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 更新源偏好(拍板 2026-10-06:自动三源链/仅直连/仅镜像;下次检查即生效) */
    val sourcePreference: StateFlow<UpdateSourcePreference> = settings.updateSourceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateSourcePreference.AUTO)

    /** 启动静默检查发现的新版本事件(MainScreen 据此弹窗或 Snackbar) */
    val foundEvents = updateCheckManager.foundEvents

    init {
        viewModelScope.launch { dismissedTag = settings.updateDismissedTagOnce() }
        // 启动静默检查的结果回填:卡片无需手动点检查即显示「发现新版本」;
        // 只回填非交互态,不覆盖用户正在进行的检查/下载/安装
        viewModelScope.launch {
            updateCheckManager.available.collect { info ->
                val s = _state.value
                if (info != null && (s is UpdateState.Idle || s is UpdateState.NoUpdate || s is UpdateState.Error)) {
                    _state.value = UpdateState.Available(info)
                }
            }
        }
        // 下载状态由前台服务驱动(拍板 2026-10-06):进度/完成/失败回填卡片;
        // 只在非交互态覆盖,不打断用户正在进行的检查
        viewModelScope.launch {
            downloadController.state.collect { ds ->
                val s = _state.value
                when (ds) {
                    is UpdateDownloadState.Downloading ->
                        if (s is UpdateState.Downloading) _state.value = UpdateState.Downloading(ds.progress)

                    is UpdateDownloadState.ReadyToInstall ->
                        _state.value = UpdateState.ReadyToInstall(ds.file, ds.tagName)

                    is UpdateDownloadState.Failed -> if (s is UpdateState.Downloading) {
                        _showInstallConfirm.value = false
                        _state.value = UpdateState.Error("下载失败：${ds.message}")
                    }

                    UpdateDownloadState.Idle -> Unit
                }
            }
        }
    }

    /** 启动检查发现新版:该版本没被「暂不」过 → 弹窗;否则降级 Snackbar */
    fun onStartupUpdateFound(info: UpdateService.UpdateInfo) {
        if (_showInstallConfirm.value) return
        if (info.tagName.isNotBlank() && info.tagName == dismissedTag) {
            snackEvents.tryEmit("发现新版本 ${info.tagName}，可在「设置 → 应用更新」下载安装")
            return
        }
        confirmFromStartup = true
        _showInstallConfirm.value = true
    }

    fun setStartupCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.saveStartupUpdateCheck(enabled) }
    }

    fun setSourcePreference(pref: UpdateSourcePreference) {
        viewModelScope.launch { settings.saveUpdateSource(pref) }
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val info = updateService.checkForUpdate()
                _state.value = if (info == null) UpdateState.NoUpdate else UpdateState.Available(info)
                // 主动检查发现新版 → 弹窗问是否下载安装(每次都弹,不受「暂不」记忆影响)
                confirmFromStartup = false
                _showInstallConfirm.value = info != null
            } catch (e: Exception) {
                // 异常类名带上,空 message 的网络异常也能定位
                _state.value = UpdateState.Error("检查失败：${e.javaClass.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { m -> "：$m" } ?: ""}")
            }
        }
    }

    /** 弹窗内「下载并安装」:开始下载并保持弹窗转为进度显示 */
    fun confirmInstall() {
        confirmFromStartup = false
        download()
    }

    /** 「后台下载」:仅收起进度弹窗,下载继续(完成仍自动拉安装器,设置卡同显进度) */
    fun hideInstallProgress() {
        _showInstallConfirm.value = false
    }

    /** 「暂不」:关弹窗;启动来源记住该版本(下次冷启动不再弹,仅 Snackbar) */
    fun dismissInstallConfirm() {
        if (confirmFromStartup) {
            (_state.value as? UpdateState.Available)?.info?.let { info ->
                if (info.tagName.isNotBlank()) {
                    dismissedTag = info.tagName
                    viewModelScope.launch { settings.saveUpdateDismissedTag(info.tagName) }
                }
            }
        }
        confirmFromStartup = false
        _showInstallConfirm.value = false
    }

    /** 确认后开始下载:交给前台服务(拍板 2026-10-06),通知进度+后台存活;卡片状态由服务状态流驱动 */
    fun download() {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        downloadController.prepare(info)
        _state.value = UpdateState.Downloading(0f)
        context.startForegroundService(Intent(context, UpdateDownloadService::class.java))
    }

    /** 拉起系统安装器(手动恢复路径,通常由服务完成后自动拉起);未授权「安装未知应用」时先跳授权页 */
    fun install() {
        val file = (downloadController.state.value as? UpdateDownloadState.ReadyToInstall)?.file ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(updateService.createInstallIntent(file))
        } else {
            // Application context 启动 Activity 必须带 NEW_TASK( Activity context 才可省)
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
