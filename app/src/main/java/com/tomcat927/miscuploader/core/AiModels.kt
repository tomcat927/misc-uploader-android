package com.tomcat927.miscuploader.core

import kotlinx.serialization.Serializable

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

/** AI 协议层异常:message 直接面向用户展示;code = HTTP 状态码(0 = 协议/解析错误) */
class AiException(message: String, val code: Int = 0) : Exception(message)

class AiNetworkException(cause: Throwable) :
    Exception("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
