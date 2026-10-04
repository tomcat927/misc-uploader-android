package com.tomcat927.miscuploader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.data.AppLogger
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.ServerConfig
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.SystemDiagnostics
import com.tomcat927.miscuploader.data.UploadMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val logText: String = "",
    val exitText: String = "",
)

data class SettingsUiState(
    val url: String = "",
    val username: String = "",
    /** 密码输入框实际内容;空 = 沿用已存(与桌面端哨兵语义一致) */
    val passwordInput: String = "",
    val hasStoredPassword: Boolean = false,
    val revealing: Boolean = false,
    val validationHint: String? = null,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val connectionMessage: String? = null,
    val connectedRootCount: Int? = null,
    /** PiGallery2 地址(D1,可选;空 = 不显示相册按钮) */
    val pigalleryBase: String = "",
    /** 分享接收目标目录(手动模式,拍板 2026-10-04;默认仓库根) */
    val defaultShareDir: String = "",
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val logger: AppLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 是否有未落盘的编辑(失焦提交用,避免首次进入空表单误报) */
    private var dirty = false

    init {
        viewModelScope.launch {
            val stored = settings.loadOnce()
            _uiState.update {
                // 拍板(2026-10-04 修订):已存密码以掩码值回显(对齐桌面端),不再用 placeholder 方案
                it.copy(
                    url = stored.baseUrl,
                    username = stored.username,
                    hasStoredPassword = stored.hasPassword,
                    passwordInput = if (stored.hasPassword) SettingsRepository.PASSWORD_MASK else "",
                )
            }
        }
        viewModelScope.launch {
            connection.state.collect { s ->
                _uiState.update {
                    when (s) {
                        is ConnectionManager.State.Idle ->
                            it.copy(connecting = false, connected = false, connectionMessage = null, connectedRootCount = null)

                        is ConnectionManager.State.Connecting ->
                            it.copy(connecting = true, connected = false, connectionMessage = null)

                        is ConnectionManager.State.Connected ->
                            it.copy(connecting = false, connected = true, connectionMessage = null, connectedRootCount = s.rootItemCount)

                        is ConnectionManager.State.Failed ->
                            it.copy(connecting = false, connected = false, connectionMessage = s.message, connectedRootCount = null)
                    }
                }
            }
        }
        viewModelScope.launch {
            val base = settings.pigalleryBaseFlow.first()
            _uiState.update { it.copy(pigalleryBase = base) }
        }
        viewModelScope.launch {
            val dir = settings.loadDefaultShareDirOnce()
            _uiState.update { it.copy(defaultShareDir = dir) }
        }
    }

    fun onUrlChange(value: String) {
        dirty = true
        _uiState.update { it.copy(url = value, validationHint = null) }
    }

    fun onUsernameChange(value: String) {
        dirty = true
        _uiState.update { it.copy(username = value, validationHint = null) }
    }

    fun onPasswordChange(value: String) {
        dirty = true
        _uiState.update { it.copy(passwordInput = value, validationHint = null) }
    }

    /** 字段失焦即落盘(拍板对齐桌面端:无保存按钮)。密码:掩码哨兵或空 = 沿用已存 */
    fun commitFields() {
        if (!dirty) return
        dirty = false
        val s = _uiState.value
        if (s.url.isBlank() && s.username.isBlank() && s.passwordInput.isEmpty()) return
        viewModelScope.launch {
            val ok = settings.save(s.url, s.username, password = s.passwordInput.takeUnless { it.isEmpty() || it == SettingsRepository.PASSWORD_MASK })
            if (!ok) {
                _uiState.update { it.copy(validationHint = "服务器地址与用户名不能为空，本次修改未保存") }
            }
        }
    }

    /** 「连接」= 保存 + 连接一步(拍板:连接本身就是最好的测试) */
    fun connect() {
        val s = _uiState.value
        viewModelScope.launch {
            val password = s.passwordInput.takeUnless { it.isEmpty() || it == SettingsRepository.PASSWORD_MASK }
            if (s.url.isBlank() || s.username.isBlank()) {
                _uiState.update { it.copy(validationHint = "服务器地址与用户名不能为空") }
                return@launch
            }
            if (password == null && !s.hasStoredPassword) {
                _uiState.update { it.copy(validationHint = "请输入密码") }
                return@launch
            }
            val saved = settings.save(s.url, s.username, password)
            if (!saved) {
                _uiState.update { it.copy(validationHint = "服务器地址与用户名不能为空") }
                return@launch
            }
            dirty = false
            val config = settings.loadDecryptedOnce()
            if (config == null) {
                _uiState.update { it.copy(validationHint = "配置不完整（缺少已保存密码）") }
                return@launch
            }
            connection.connect(normalize(config))
        }
    }

    /** 「显示」:取回真实密码;「隐藏」:回到掩码值(输入即替换语义与桌面端一致) */
    fun toggleReveal() {
        val s = _uiState.value
        if (!s.revealing) {
            if (!s.hasStoredPassword) return
            viewModelScope.launch {
                val real = settings.revealPassword()
                if (real != null) {
                    _uiState.update { it.copy(revealing = true, passwordInput = real) }
                }
            }
        } else {
            _uiState.update { it.copy(revealing = false, passwordInput = SettingsRepository.PASSWORD_MASK) }
        }
    }

    private fun normalize(config: ServerConfig): ServerConfig =
        config.copy(baseUrl = SettingsRepository.normalizeBaseUrl(config.baseUrl))

    // ---- 上传模式(A2) ----

    val uploadMode: StateFlow<UploadMode> = settings.uploadModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadMode.MANUAL)

    fun setUploadMode(mode: UploadMode) {
        viewModelScope.launch { settings.saveUploadMode(mode) }
    }

    // ---- 双栏触摸聚焦(2026-10-04 拍板:可关,默认开) ----

    val touchFocus: StateFlow<Boolean> = settings.touchFocusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setTouchFocus(enabled: Boolean) {
        viewModelScope.launch { settings.saveTouchFocus(enabled) }
    }

    // ---- 显示隐藏文件(2026-10-04 拍板:默认关) ----

    val showHidden: StateFlow<Boolean> = settings.showHiddenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setShowHidden(enabled: Boolean) {
        viewModelScope.launch { settings.saveShowHidden(enabled) }
    }

    // ---- PiGallery2 深链(D1:可选地址,失焦即存) ----

    private var pigalleryDirty = false

    fun onPigalleryChange(value: String) {
        pigalleryDirty = true
        _uiState.update { it.copy(pigalleryBase = value) }
    }

    fun commitPigallery() {
        if (!pigalleryDirty) return
        pigalleryDirty = false
        viewModelScope.launch { settings.savePigalleryBase(_uiState.value.pigalleryBase) }
    }

    // ---- 上传设置(拍板 2026-10-04:并发/最大重试/仅 Wi-Fi/默认分享目录) ----

    val uploadConcurrency: StateFlow<Int> = settings.uploadConcurrencyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    val uploadMaxRetries: StateFlow<Int> = settings.uploadMaxRetriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    val wifiOnly: StateFlow<Boolean> = settings.wifiOnlyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUploadConcurrency(value: Int) {
        viewModelScope.launch { settings.saveUploadConcurrency(value) }
    }

    fun setUploadMaxRetries(value: Int) {
        viewModelScope.launch { settings.saveUploadMaxRetries(value) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settings.saveWifiOnly(enabled) }
    }

    private var shareDirDirty = false

    fun onShareDirChange(value: String) {
        shareDirDirty = true
        _uiState.update { it.copy(defaultShareDir = value) }
    }

    fun commitShareDir() {
        if (!shareDirDirty) return
        shareDirDirty = false
        viewModelScope.launch { settings.saveDefaultShareDir(_uiState.value.defaultShareDir) }
    }

    // ---- 诊断(M4:零本地环境下的排查窗口) ----

    fun toggleDiagnostics() {
        val expanded = !_uiState.value.diagnostics.expanded
        _uiState.update { it.copy(diagnostics = it.diagnostics.copy(expanded = expanded)) }
        if (expanded) loadDiagnostics()
    }

    fun loadDiagnostics() {
        _uiState.update { it.copy(diagnostics = it.diagnostics.copy(loading = true)) }
        viewModelScope.launch {
            val logText = logger.readRecent()
            val exitText = SystemDiagnostics.exitInfo(context)
            _uiState.update {
                it.copy(diagnostics = it.diagnostics.copy(loading = false, logText = logText, exitText = exitText))
            }
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch {
            logger.clear()
            loadDiagnostics()
        }
    }
}
