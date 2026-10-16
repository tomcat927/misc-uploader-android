package com.tomcat927.miscuploader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.core.AiClient
import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.core.AiTools
import com.tomcat927.miscuploader.data.AiToolRunner
import com.tomcat927.miscuploader.data.AppLogger
import com.tomcat927.miscuploader.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class AiChatUiState(
    val messages: List<AiMessage> = emptyList(),
    val busy: Boolean = false,
    val stage: String? = null,
    val error: String? = null,
)

/**
 * AI 助手会话式 agent(拍板 2026-10-16)。
 * P3:单会话 + 只读工具后台自动执行;动作工具与多会话持久化在 P4/P5 接入。
 */
@HiltViewModel
class AiAgentViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val runner: AiToolRunner,
    private val logger: AppLogger,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AiChatUiState())
    val state: StateFlow<AiChatUiState> = _state.asStateFlow()

    /** AI 助手总开关(未启用时 UI 给引导) */
    val aiEnabled: StateFlow<Boolean> = settings.aiEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 会话消息(送给模型 + 用于展示) */
    private var history: List<AiMessage> = emptyList()
    private var job: Job? = null

    /** 发送一条用户消息并跑 agent 循环 */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || job?.isActive == true) return
        history = history + AiMessage(role = "user", text = t)
        publish()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, stage = "思考中…") }
            try {
                val stored = settings.aiStoredConfigFlow.first()
                if (!stored.enabled) throw IllegalStateException("AI 助手未启用（设置 → AI 助手）")
                val cfg = settings.loadAiDecryptedOnce()
                    ?: throw IllegalStateException("未配置 AI 助手（设置 → AI 助手）")
                if (cfg.model.isBlank()) throw IllegalStateException("未选择 AI 模型（设置 → AI 助手）")
                val client = AiClient(cfg.baseUrl, cfg.apiKey, okHttpClient) { logger.log("ai", it) }
                val tools = AiTools.all
                    .filter { it.kind == AiTools.Kind.READ_ONLY }
                    .map { it.tool }

                var iter = 0
                while (iter++ < MAX_ITERS) {
                    val result = client.chatWithTools(cfg.model, historyForRequest(), tools, SYSTEM_PROMPT)
                    history = history + AiMessage(
                        role = "assistant",
                        text = result.text,
                        toolCalls = result.toolCalls,
                    )
                    publish()
                    if (result.toolCalls.isEmpty()) break
                    _state.update { it.copy(stage = "调用工具…") }
                    result.toolCalls.forEach { tc ->
                        val spec = AiTools.spec(tc.name)
                        if (spec == null || spec.kind != AiTools.Kind.READ_ONLY) {
                            history = history + AiMessage(
                                role = "tool",
                                text = "错误：工具 ${tc.name} 不可用。",
                                toolCallId = tc.id,
                            )
                        } else {
                            logger.log("ai", "调用工具 ${tc.name}(${tc.arguments.take(200)})")
                            val outcome = runner.run(tc)
                            history = history + AiMessage(role = "tool", text = outcome.text, toolCallId = tc.id)
                            if (outcome.images.isNotEmpty()) {
                                history = history + AiMessage(role = "user", text = null, images = outcome.images)
                            }
                        }
                    }
                    publish()
                    _state.update { it.copy(stage = "思考中…") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                logger.log("ai", "AI 助手对话失败：$msg")
                _state.update { it.copy(error = msg) }
            } finally {
                _state.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    /** 取消进行中的一轮 */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(busy = false, stage = null) }
    }

    /** 清空当前会话 */
    fun clear() {
        job?.cancel()
        job = null
        history = emptyList()
        _state.update { AiChatUiState() }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun publish() {
        _state.update { it.copy(messages = history) }
    }

    /** 请求用的历史:只保留最后一条带图消息,避免 base64 图片逐轮累积烧 token */
    private fun historyForRequest(): List<AiMessage> {
        val lastImageIdx = history.indexOfLast { it.images.isNotEmpty() }
        if (lastImageIdx < 0) return history
        return history.filterIndexed { i, m -> m.images.isEmpty() || i == lastImageIdx }
    }

    companion object {
        /** 单轮对话最多工具往返次数,防模型打转/烧钱 */
        private const val MAX_ITERS = 8

        private val SYSTEM_PROMPT = """
            你是「杂物仓库」Android 客户端的文件归档助手。用户手机上的文件需要整理归档到 OpenList 仓库。
            你可以调用工具查看本地文件、读取图片、查看仓库目录结构、搜索文件、查看待清理列表。
            请先弄清用户意图,再按需调用工具收集信息,然后给出清晰建议:
            - 哪些文件/文件夹建议上传到仓库的哪个目录(尽量沿用仓库已有目录结构);
            - 上传完成后本地原件如何处理(不管 / 进待清理列表 / 立即清理)。
            只依据工具返回的真实文件名与路径,不要编造不存在的文件。用中文、简洁、可执行。
            注意:你只能建议,真正的上传与删除需用户在界面上确认。
        """.trimIndent()
    }
}
