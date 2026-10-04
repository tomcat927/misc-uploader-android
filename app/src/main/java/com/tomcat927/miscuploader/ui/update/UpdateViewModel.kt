package com.tomcat927.miscuploader.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.update.UpdateCheckManager
import com.tomcat927.miscuploader.update.UpdateService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
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
    private val updateService: UpdateService,
    private val updateCheckManager: UpdateCheckManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloaded: File? = null

    /** 启动静默检查发现的新版本事件(Main 页 Snackbar 提示用) */
    val foundEvents = updateCheckManager.foundEvents

    /** 启动检查更新开关(设置「应用更新」卡内切换) */
    val startupCheckEnabled: StateFlow<Boolean> = settings.startupUpdateCheckFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
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
    }

    fun setStartupCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.saveStartupUpdateCheck(enabled) }
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val info = updateService.checkForUpdate()
                _state.value = if (info == null) UpdateState.NoUpdate else UpdateState.Available(info)
            } catch (e: Exception) {
                // 异常类名带上,空 message 的网络异常也能定位
                _state.value = UpdateState.Error("检查失败：${e.javaClass.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { m -> "：$m" } ?: ""}")
            }
        }
    }

    fun download() {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        viewModelScope.launch {
            _state.value = UpdateState.Downloading(0f)
            try {
                val file = updateService.downloadApk(info) { p ->
                    _state.value = UpdateState.Downloading(p)
                }
                downloaded = file
                _state.value = UpdateState.ReadyToInstall(file, info.tagName)
            } catch (e: Exception) {
                _state.value = UpdateState.Error("下载失败：${e.javaClass.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { m -> "：$m" } ?: ""}")
            }
        }
    }

    /** 未授权「安装未知应用」时跳系统设置(带 package URI 直达本应用开关);授权后用户回来再点一次安装 */
    fun install(context: Context) {
        val file = downloaded ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(updateService.createInstallIntent(file))
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(intent)
        }
    }
}
