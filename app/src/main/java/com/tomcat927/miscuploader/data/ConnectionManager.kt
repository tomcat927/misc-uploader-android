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
    private val logger: AppLogger,
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
        // 诊断日志(2026-10-05):目标地址+端口缺失提示——ECONNREFUSED 最常见原因就是地址没带 :端口
        val portHint = runCatching {
            if (java.net.URI(config.baseUrl).port == -1) {
                "（地址未带端口：将连默认 443/80，若服务不在该端口请补如 :5245）"
            } else {
                ""
            }
        }.getOrDefault("")
        logger.log("connect", "开始连接 ${config.baseUrl}$portHint")
        try {
            val client = OpenListClient(
                baseUrl = config.baseUrl,
                username = config.username,
                password = config.password,
                baseClient = okHttpClient,
                // HTTP 层日志(方法/路径/状态码/耗时/长度;非 JSON 记脱敏片段)进诊断卡
                debugLog = { msg -> logger.log("http", msg) },
            )
            client.login()
            logger.log("connect", "登录成功 ${config.baseUrl}")
            val entries = client.list("/")
            _state.value = State.Connected(client, entries.size)
            logger.log("connect", "已连接（根目录 ${entries.size} 项）")
        } catch (e: OpenListApiException) {
            logger.log("connect", "连接失败（API code=${e.code}）：${e.message} · 目标 ${config.baseUrl}")
            _state.value = State.Failed(e.message ?: "请求失败")
        } catch (e: IOException) {
            logger.log("connect", "连接失败（网络 ${e.javaClass.simpleName}）：${e.message} · 目标 ${config.baseUrl}")
            _state.value = State.Failed("网络错误：${e.message ?: "无法连接"}")
        } catch (e: Exception) {
            logger.log("connect", "连接失败（${e.javaClass.simpleName}）：${e.message} · 目标 ${config.baseUrl}")
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
