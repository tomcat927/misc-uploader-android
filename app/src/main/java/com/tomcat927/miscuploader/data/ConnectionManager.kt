package com.tomcat927.miscuploader.data

import com.tomcat927.miscuploader.core.OpenListClient
import com.tomcat927.miscuploader.core.OpenListApiException
import com.tomcat927.miscuploader.core.RemoteStorage
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * 连接状态(拍板对齐桌面端:「已连接」= 内存里持一次成功 login 的会话;
 * 配置齐全时启动自动连接;WebDAV 无,全部 REST 单认证链路)。
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val settings: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val client: OpenListClient, val rootItemCount: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** M2 起双栏/队列从这取当前会话;未连接返回 null */
    fun clientOrNull(): RemoteStorage? = (_state.value as? State.Connected)?.client

    suspend fun connect(config: ServerConfig) {
        _state.value = State.Connecting
        try {
            val client = OpenListClient(
                baseUrl = config.baseUrl,
                username = config.username,
                password = config.password,
                baseClient = okHttpClient,
            )
            client.login()
            val entries = client.list("/")
            _state.value = State.Connected(client, entries.size)
        } catch (e: OpenListApiException) {
            _state.value = State.Failed(e.message ?: "请求失败")
        } catch (e: IOException) {
            _state.value = State.Failed("网络错误：${e.message ?: "无法连接"}")
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 启动自动连接(拍板:配置齐全时启动即连) */
    suspend fun autoConnectIfConfigured() {
        if (_state.value !is State.Idle) return
        val config = settings.loadDecryptedOnce() ?: return
        connect(config)
    }
}
