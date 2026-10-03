package com.tomcat927.miscuploader.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenList 统一响应壳:非 200 一律视为失败(见 client-protocol-decision.md 防御清单) */
@Serializable
data class OpenListResponse<T>(
    val code: Int,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
data class LoginData(val token: String)

@Serializable
data class FsListRequest(
    val path: String,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 1000,
    val refresh: Boolean = false,
    val password: String = "",
)

@Serializable
data class FsListData(
    val content: List<FsEntry>? = null,
    val total: Long = 0,
)

@Serializable
data class FsEntry(
    val name: String,
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    val modified: String? = null,
)

/** 协议层异常:message 直接面向用户展示 */
class OpenListApiException(val code: Int, message: String) : Exception(message)

class OpenListNetworkException(cause: Throwable) :
    Exception("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
