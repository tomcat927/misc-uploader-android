package com.tomcat927.miscuploader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** GET /models 响应(OpenAI 兼容;未知字段忽略) */
@Serializable
data class AiModelsResponse(
    val data: List<AiModelEntry> = emptyList(),
)

@Serializable
data class AiModelEntry(
    val id: String,
    val owned_by: String? = null,
)

/** 多模态输入的一张图片(base64 内联,data URI 用) */
data class AiImage(val base64: String, val mime: String)

/** 工具定义(映射 OpenAI `tools[].function`) */
data class AiTool(
    val name: String,
    val description: String,
    /** JSON Schema 对象(`parameters`) */
    val parameters: JsonObject,
)

/** 模型请求的一次工具调用;arguments 为原始 JSON 字符串,由调用方解析 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * 对话消息(多轮 + 工具往返统一载体)。
 * role = system/user/assistant/tool;images 仅 user 用;toolCalls 仅 assistant 用;toolCallId 仅 tool 用。
 */
data class AiMessage(
    val role: String,
    val text: String? = null,
    val images: List<AiImage> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** 一次 chat 的结果:文本(可空)+ 工具调用 + 结束原因 */
data class AiChatResult(
    val text: String?,
    val toolCalls: List<AiToolCall>,
    val finishReason: String?,
)

/** AI 协议层异常:message 直接面向用户展示;code = HTTP 状态码(0 = 协议/解析错误) */
class AiException(message: String, val code: Int = 0) : Exception(message)

class AiNetworkException(cause: Throwable) :
    Exception("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
