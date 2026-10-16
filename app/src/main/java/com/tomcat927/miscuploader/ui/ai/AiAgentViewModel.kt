package com.tomcat927.miscuploader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.core.AiClient
import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.core.AiToolCall
import com.tomcat927.miscuploader.core.AiTools
import com.tomcat927.miscuploader.core.optString
import com.tomcat927.miscuploader.core.optStringList
import com.tomcat927.miscuploader.data.AiToolRunner
import com.tomcat927.miscuploader.data.AppLogger
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.ToolOutcome
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

/** 待用户确认的动作工具调用 */
data class PendingAction(
    val id: String,
    val title: String,
    val lines: List<String>,
)

data class AiChatUiState(
    val messages: List<AiMessage> = emptyList(),
    val busy: Boolean = false,
    val stage: String? = null,
    val error: String? = null,
    /** 非空 = 有一个动作工具待用户确认(确认前循环暂停) */
    val pendingAction: PendingAction? = null,
)

/**
 * AI 助手会话式 agent(拍板 2026-10-16)。
 * 只读工具后台自动执行;动作工具(enqueue_upload/trash_local)暂停循环等用户确认后再执行。
 * P4:动作工具 + 确认;多会话持久化在 P5。
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

    private var history: List<AiMessage> = emptyList()

    /** 已由模型发起、尚未回填结果的工具调用(动作工具在此排队等确认) */
    private var pendingToolCalls: List<AiToolCall> = emptyList()
    private var sessionClient: AiClient? = null
    private var sessionModel: String? = null
    private var job: Job? = null

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || job?.isActive == true || _state.value.pendingAction != null) return
        history = history + AiMessage(role = "user", text = t)
        publish()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, stage = "思考中…") }
            try {
                if (prepareSession()) drive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                _state.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    /** 用户确认执行当前待确认动作 */
    fun approveAction() {
        val tc = pendingToolCalls.firstOrNull() ?: return
        _state.update { it.copy(pendingAction = null, busy = true, stage = "执行中…") }
        job = viewModelScope.launch {
            try {
                logger.log("ai", "用户确认动作 ${tc.name}")
                val outcome = runner.run(tc)
                pendingToolCalls = pendingToolCalls.drop(1)
                appendToolResult(tc, outcome)
                drive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                _state.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    /** 用户拒绝当前待确认动作:回填「已拒绝」并继续循环 */
    fun rejectAction() {
        val tc = pendingToolCalls.firstOrNull() ?: return
        _state.update { it.copy(pendingAction = null, busy = true, stage = null) }
        job = viewModelScope.launch {
            try {
                logger.log("ai", "用户拒绝动作 ${tc.name}")
                pendingToolCalls = pendingToolCalls.drop(1)
                appendToolResult(tc, ToolOutcome("用户拒绝执行该操作。"))
                drive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                _state.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    /** 取消进行中的一轮 */
    fun cancel() {
        job?.cancel()
        job = null
        pendingToolCalls = emptyList()
        _state.update { it.copy(busy = false, stage = null, pendingAction = null) }
    }

    /** 清空当前会话 */
    fun clear() {
        job?.cancel()
        job = null
        history = emptyList()
        pendingToolCalls = emptyList()
        sessionClient = null
        sessionModel = null
        _state.update { AiChatUiState() }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---- 内部 ----

    private suspend fun prepareSession(): Boolean {
        val stored = settings.aiStoredConfigFlow.first()
        if (!stored.enabled) {
            onError(IllegalStateException("AI 助手未启用（设置 → AI 助手）"))
            return false
        }
        val cfg = settings.loadAiDecryptedOnce()
        if (cfg == null) {
            onError(IllegalStateException("未配置 AI 助手（设置 → AI 助手）"))
            return false
        }
        if (cfg.model.isBlank()) {
            onError(IllegalStateException("未选择 AI 模型（设置 → AI 助手）"))
            return false
        }
        sessionClient = AiClient(cfg.baseUrl, cfg.apiKey, okHttpClient) { logger.log("ai", it) }
        sessionModel = cfg.model
        return true
    }

    /** agent 主循环:回填工具结果 → 请求模型 → 遇动作工具暂停等确认,直到模型给出最终答复 */
    private suspend fun drive() {
        val client = sessionClient ?: return
        val model = sessionModel ?: return
        val tools = AiTools.toolDefs()
        var turns = 0
        while (turns++ < MAX_TURNS) {
            while (pendingToolCalls.isNotEmpty()) {
                val tc = pendingToolCalls.first()
                val spec = AiTools.spec(tc.name)
                if (spec?.kind == AiTools.Kind.ACTION) {
                    _state.update { it.copy(pendingAction = describeAction(tc), busy = false, stage = null) }
                    return
                }
                pendingToolCalls = pendingToolCalls.drop(1)
                logger.log("ai", "调用工具 ${tc.name}（${tc.arguments.take(200)}）")
                appendToolResult(tc, runner.run(tc))
                _state.update { it.copy(stage = "思考中…") }
            }
            _state.update { it.copy(stage = "思考中…") }
            val result = client.chatWithTools(model, historyForRequest(), tools, SYSTEM_PROMPT)
            history = history + AiMessage(role = "assistant", text = result.text, toolCalls = result.toolCalls)
            pendingToolCalls = result.toolCalls
            publish()
            if (pendingToolCalls.isEmpty()) return
        }
        logger.log("ai", "达到最大工具轮数 $MAX_TURNS，停止")
    }

    private fun appendToolResult(tc: AiToolCall, outcome: ToolOutcome) {
        history = history + AiMessage(role = "tool", text = outcome.text, toolCallId = tc.id)
        if (outcome.images.isNotEmpty()) {
            history = history + AiMessage(role = "user", text = null, images = outcome.images)
        }
        publish()
    }

    private fun describeAction(tc: AiToolCall): PendingAction {
        val args = AiTools.parseArgs(tc.arguments)
        return when (tc.name) {
            AiTools.ENQUEUE_UPLOAD -> {
                val items = args.optStringList(AiTools.ARG_ITEMS)
                val target = args.optString(AiTools.ARG_TARGET_DIR) ?: "/"
                val cleanup = when (args.optString(AiTools.ARG_CLEANUP_MODE)) {
                    AiTools.CLEANUP_IMMEDIATE -> "上传后本地立即清理（移入回收站）"
                    AiTools.CLEANUP_NONE -> "上传后不动本地文件"
                    else -> "上传后本地进入待清理列表"
                }
                val tail = if (items.size > 20) listOf("… 等 ${items.size} 项") else emptyList()
                PendingAction(
                    id = tc.id,
                    title = "加入上传队列",
                    lines = listOf("目标目录：$target", "上传后：$cleanup", "共 ${items.size} 项：") +
                        items.take(20) + tail,
                )
            }

            AiTools.TRASH_LOCAL -> {
                val paths = args.optStringList(AiTools.ARG_PATHS)
                val tail = if (paths.size > 20) listOf("… 等 ${paths.size} 项") else emptyList()
                PendingAction(
                    id = tc.id,
                    title = "删除本地文件（移入回收站，可恢复）",
                    lines = listOf("共 ${paths.size} 项：") + paths.take(20) + tail,
                )
            }

            else -> PendingAction(tc.id, tc.name, listOf(tc.arguments.take(300)))
        }
    }

    private fun onError(e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        logger.log("ai", "AI 助手对话失败：$msg")
        _state.update { it.copy(error = msg) }
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
        /** 单轮对话最多模型往返次数,防模型打转/烧钱 */
        private const val MAX_TURNS = 8

        private val SYSTEM_PROMPT = """
            你是「杂物仓库」Android 客户端的文件归档助手。用户手机上的文件需要整理归档到 OpenList 仓库。
            你可以调用工具查看本地文件、读取图片、查看仓库目录结构、搜索文件、查看待清理列表。
            请先弄清用户意图,再按需调用工具收集信息,然后给出清晰建议:
            - 哪些文件/文件夹建议上传到仓库的哪个目录(尽量沿用仓库已有目录结构);
            - 上传完成后本地原件如何处理(不管 / 进待清理列表 / 立即清理)。
            只依据工具返回的真实文件名与路径,不要编造不存在的文件。用中文、简洁、可执行。
            上传与删除是敏感操作,系统会在真正执行前让用户确认,你可以放心提出建议。
        """.trimIndent()
    }
}
