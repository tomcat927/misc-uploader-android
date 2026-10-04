package com.tomcat927.miscuploader.core

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    /** 诊断日志缝(2026-10-05):HTTP 层方法/路径/状态码/耗时/长度;连接管理器注入 AppLogger。null = 静默 */
    private val debugLog: ((String) -> Unit)? = null,
) : RemoteStorage {

    private val baseUrl = normalizeBaseUrl(baseUrl)
    // encodeDefaults=true:默认值必须显式编码(per_page/refresh 省略会让服务端回落默认页大小,大目录截断)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
                    // OkHttp 回调线程(非协程):阻塞式重登一次,仅此一处允许 runBlocking
                    runBlocking { runCatching { loginInternal() } }
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

    override suspend fun mkdirp(remotePath: String) {
        val parent = remotePath.substringBeforeLast('/', missingDelimiterValue = "/")
        if (parent.isEmpty() || parent == "/") return
        var cur = ""
        for (seg in parent.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = "$cur/$seg"
            runCatching { mkdir(cur) } // 已存在等错误一律忽略(对齐 misc-sync.py)
        }
    }

    override suspend fun fileInfo(remotePath: String): RemoteFileInfo {
        val body = postJson(
            "$baseUrl/api/fs/get",
            json.encodeToString(FsGetRequest.serializer(), FsGetRequest(path = remotePath)),
        )
        val data = requireData(body) { json.decodeFromString<FsGetData>(it.toString()) }
        if (data.isDir) throw OpenListApiException(400, "路径是文件夹，不是文件")
        return RemoteFileInfo(name = data.name, size = data.size, rawUrl = fixRawUrl(data.rawUrl))
    }

    override suspend fun downloadTo(
        remotePath: String,
        target: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val info = fileInfo(remotePath)
        val request = Request.Builder().url(info.rawUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OpenListApiException(response.code, "下载失败（HTTP ${response.code}）")
            }
            val total = response.body?.contentLength()?.takeIf { it > 0 } ?: info.size
            var sent = 0L
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress(sent, total)
                    }
                }
            } ?: throw OpenListApiException(500, "响应无内容")
        }
    }

    // 字段名已对照 OpenList 源码 server/handles/fsmanage.go 核实(坑 5:不凭训练数据硬写):
    // fs/move = {src_dir,dst_dir,names}, fs/remove = {dir,names};权限位 CanMove/CanRemove
    override suspend fun move(srcDir: String, names: List<String>, dstDir: String) {
        val body = postJson(
            "$baseUrl/api/fs/move",
            json.encodeToString(
                MoveRequest.serializer(),
                MoveRequest(srcDir = srcDir, dstDir = dstDir, names = names),
            ),
        )
        requireCode(body)
    }

    override suspend fun remove(dir: String, names: List<String>) {
        val body = postJson(
            "$baseUrl/api/fs/remove",
            json.encodeToString(RemoveRequest.serializer(), RemoveRequest(dir = dir, names = names)),
        )
        requireCode(body)
    }

    /**
     * raw_url host 修正(避坑指南实测坑:有些部署返回的 raw_url 主机/端口与连接地址不同,
     * 如 127.0.0.1:5244 直跑地址)——取其 path+query 拼到当前连接的 scheme+authority。
     */
    private fun fixRawUrl(raw: String): String = runCatching {
        val u = java.net.URI(raw)
        if (u.host == null) return raw
        val base = java.net.URI(baseUrl)
        java.net.URI(base.scheme, base.authority, u.path, u.query, null).toString()
    }.getOrDefault(raw)

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

    private suspend fun execute(request: Request): String {
        val started = System.currentTimeMillis()
        return try {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                debugLog?.invoke(
                    "${request.method} ${request.url.encodedPath} → HTTP ${response.code}，" +
                        "${System.currentTimeMillis() - started}ms，${text.length} 字符",
                )
                // 响应体入日志(用户拍板 2026-10-05):token 一律脱敏;大响应截断记片段
                debugLog?.invoke(
                    "响应体：${scrub(if (text.length <= 1024) text else text.take(300) + "…(截断)")}",
                )
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
            debugLog?.invoke("请求异常 ${request.method} ${request.url.encodedPath}：${e.javaClass.simpleName}：${e.message}")
            throw OpenListNetworkException(e)
        }
    }

    /**
     * 日志脱敏:响应体可能含 token(登录响应),原样入日志等于泄凭据——
     * 片段日志一律先过此函数。请求体(含密码)永不入日志。
     */
    private fun scrub(s: String): String =
        s.replace(Regex("\"token\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "\"token\":\"***\"")

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

@Serializable
private data class MoveRequest(
    @SerialName("src_dir") val srcDir: String,
    @SerialName("dst_dir") val dstDir: String,
    val names: List<String>,
)

@Serializable
private data class RemoveRequest(val dir: String, val names: List<String>)

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
