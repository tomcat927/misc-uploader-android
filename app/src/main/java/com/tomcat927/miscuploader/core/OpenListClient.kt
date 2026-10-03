package com.tomcat927.miscuploader.core

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenList 纯 REST 客户端(拍板见 client-protocol-decision.md)。
 *
 * 防御三件套:
 * 1. 响应强制 JSON 解析 + code==200,非 JSON(打错路由/反代兜底页的 200+HTML)一律失败
 * 2. 401 只自动重登一次(Authenticator + 一次性标记头防循环;登录请求本身不重试)
 * 3. File-Path 头统一走 [encodeFilePath],禁止裸拼
 *
 * 线程模型:token 的读写都在 OkHttp 回调线程或调用协程;重登走 synchronized 单飞,
 * 其他线程以"请求头里的旧 token != 当前 token"判断已被刷新过,直接复用新 token。
 */
class OpenListClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    baseClient: OkHttpClient,
) : RemoteStorage {

    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var currentToken: String? = null

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = currentToken
            val req = if (token != null && chain.request().header("Authorization") == null) {
                chain.request().newBuilder().header("Authorization", token).build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }
        .authenticator { _, response ->
            if (response.request.header(RETRY_HEADER) != null) return@authenticator null
            if (response.request.url.encodedPath.endsWith("/api/auth/login")) return@authenticator null
            val fresh = synchronized(this) {
                val stale = response.request.header("Authorization")
                if (currentToken != null && currentToken != stale) {
                    currentToken
                } else {
                    runCatching { loginInternal() }.getOrNull()
                    currentToken
                }
            } ?: return@authenticator null
            response.request.newBuilder()
                .header("Authorization", fresh)
                .header(RETRY_HEADER, "1")
                .build()
        }
        .build()

    override suspend fun login() {
        loginInternal()
    }

    override suspend fun list(path: String, page: Int, refresh: Boolean): List<FsEntry> {
        val body = postJson(
            "$baseUrl/api/fs/list",
            json.encodeToString(
                FsListRequest.serializer(),
                FsListRequest(path = path, page = page, refresh = refresh),
            ),
        )
        return requireData(body) { json.decodeFromString<FsListData>(it.toString()) }.content.orEmpty()
    }

    override suspend fun mkdir(path: String) {
        val body = postJson(
            "$baseUrl/api/fs/mkdir",
            json.encodeToString(MkdirRequest.serializer(), MkdirRequest(path = path)),
        )
        requireCode(body)
    }

    override suspend fun upload(
        file: File,
        remotePath: String,
        overwrite: Boolean,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        val req = Request.Builder()
            .url("$baseUrl/api/fs/put")
            .header("File-Path", encodeFilePath(remotePath))
            .header("Overwrite", if (overwrite) "true" else "false")
            .put(ProgressRequestBody(file, onProgress))
            .build()
        requireCode(execute(req))
    }

    // ---- 内部 ----

    private suspend fun loginInternal(): String {
        val body = postJson(
            "$baseUrl/api/auth/login",
            json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(username = username, password = password),
            ),
        )
        return requireData(body) { json.decodeFromString<LoginData>(it.toString()).token }.also { currentToken = it }
    }

    private suspend fun postJson(url: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return execute(req)
    }

    private suspend fun execute(request: Request): String = try {
        client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            // 防御 1:非 JSON(打错路由拿到 SPA index.html / 反代错误页)一律失败
            if (!text.trimStart().startsWith("{")) {
                throw OpenListApiException(
                    response.code,
                    "响应不是 JSON（HTTP ${response.code}），请检查地址是否指向 OpenList（形如 https://host:5245）",
                )
            }
            text
        }
    } catch (e: OpenListApiException) {
        throw e
    } catch (e: Exception) {
        throw OpenListNetworkException(e)
    }

    /** 只校验 code==200(目录/上传的 data 不关心) */
    private fun requireCode(body: String) {
        val resp = json.decodeFromString<OpenListResponse<JsonElement>>(body)
        if (resp.code != 200) {
            throw OpenListApiException(resp.code, "${resp.message ?: "请求失败"}（code=${resp.code}）")
        }
    }

    /** 校验 code==200 并取 data 反序列化 */
    private inline fun <T> requireData(body: String, transform: (JsonElement) -> T): T {
        val resp = json.decodeFromString<OpenListResponse<JsonElement>>(body)
        if (resp.code != 200) {
            throw OpenListApiException(resp.code, "${resp.message ?: "请求失败"}（code=${resp.code}）")
        }
        val element = resp.data ?: throw OpenListApiException(resp.code, "响应缺少 data")
        return transform(element)
    }

    private fun normalizeBaseUrl(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.trimEnd('/')
    }

    companion object {
        private const val RETRY_HEADER = "X-Misc-Token-Retry"

        /**
         * File-Path 头编码:按段 percent-encode(空格→%20),服务端 PathUnescape 还原后拼 base_path。
         * URLEncoder 是表单编码(空格→+),必须替换;"/" 作为路径分隔符保留。
         */
        fun encodeFilePath(path: String): String {
            val normalized = "/" + path.trimStart('/')
            return normalized.split('/').filter { it.isNotEmpty() }.joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }.let { "/$it" }
        }
    }
}

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class MkdirRequest(val path: String)

/** 客户端字节计数(拍板:上传进度的唯一现实来源,协议无关) */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
}
