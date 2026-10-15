package com.tomcat927.miscuploader.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AI 助手(OpenAI 兼容)客户端。
 *
 * 设计原则(对齐 OpenListClient):
 * - 只做"取信息":拉模型列表 + 对话(文本/多模态)。执行动作永远走 app 既有路径,AI 只出建议。
 * - 地址 = OpenAI 兼容 base(含 /v1);端点 = base + /models、base + /chat/completions。
 * - 网络 I/O 全部在 Dispatchers.IO(坑 7);非 2xx 直接抛,不吞错。
 * - 请求体/密钥永不入日志;debugLog 只记方法/路径/状态码/长度。
 */
class AiClient(
    baseUrl: String,
    private val apiKey: String,
    baseClient: OkHttpClient,
    /** 诊断日志缝;null = 静默 */
    private val debugLog: ((String) -> Unit)? = null,
) {

    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 多模态推理较慢,读超时放宽
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 拉取模型列表(GET /models),返回按名称排序的模型 id(空 id 剔除) */
    suspend fun listModels(): List<String> {
        val req = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val body = execute(req)
        return json.decodeFromString<AiModelsResponse>(body).data
            .map { it.id }
            .filter { it.isNotBlank() }
            .sorted()
    }

    /** 纯文本对话(P1 预留;当前 UI 只用到 listModels / verifyVision) */
    suspend fun chatText(model: String, userText: String, systemPrompt: String? = null): String {
        val messages = buildJsonArray {
            if (systemPrompt != null) add(systemMessage(systemPrompt))
            add(buildJsonObject { put("role", "user"); put("content", userText) })
        }
        return chat(model, messages)
    }

    /** 多模态对话:文本 + 若干张图片(base64,data URI 内联) */
    suspend fun chatVision(
        model: String,
        userText: String,
        images: List<AiImage>,
        systemPrompt: String? = null,
    ): String {
        val messages = buildJsonArray {
            if (systemPrompt != null) add(systemMessage(systemPrompt))
            add(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", userText) })
                            images.forEach { img ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            buildJsonObject { put("url", "data:${img.mime};base64,${img.base64}") },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return chat(model, messages)
    }

    /** 多模态能力探针:发一张 64×64 纯色 PNG,能拿到文本回复即视为该模型接受图片输入 */
    suspend fun verifyVision(model: String): String =
        chatVision(model, PROBE_PROMPT, listOf(AiImage(PROBE_PNG_BASE64, "image/png")))

    // ---- 内部 ----

    private fun systemMessage(text: String) =
        buildJsonObject { put("role", "system"); put("content", text) }

    private suspend fun chat(model: String, messages: JsonArray): String {
        val payload = buildJsonObject {
            put("model", model)
            put("messages", messages)
            // 推理模型把「思考」也算进 max_tokens:额度太小会思考占满、content 为空(实测踩过)
            put("max_tokens", MAX_TOKENS)
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val body = execute(req)
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw AiException("响应不是 JSON：${body.take(200)}") }
        val choices = root["choices"]?.jsonArray ?: throw AiException("响应缺少 choices")
        val choice = choices.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val text = message?.let { messageText(it) }
            ?: choice?.get("text")?.jsonPrimitive?.contentOrNull // 兼容 legacy completions 的 choices[].text
        if (text.isNullOrBlank()) {
            debugLog?.invoke("ai: 响应无可用文本（前 400 字符）：${body.take(400)}")
            throw AiException(if (message == null) "响应缺少 message" else "响应缺少 message.content")
        }
        return text
    }

    /**
     * 取 message 的文本:优先 content;缺失/空白时回落到 reasoning_content。
     * 推理模型(max_tokens 被思考吃满)会只回思考、content 为空,直接抛错会白跑一次分析。
     */
    private fun messageText(message: JsonObject): String? {
        val direct = textOf(message["content"])
        if (!direct.isNullOrBlank()) return direct
        return textOf(message["reasoning_content"])?.takeIf { it.isNotBlank() }
            ?: textOf(message["reasoning"])?.takeIf { it.isNotBlank() }
    }

    /** content 可能是字符串、null、或多段 [{type:text,text}] */
    private fun textOf(el: JsonElement?): String? = when (el) {
        null, is JsonNull -> null
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        is JsonArray -> el.joinToString("") { child ->
            (child as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        }.ifBlank { null }

        else -> el.toString()
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                debugLog?.invoke(
                    "${request.method} ${request.url.encodedPath} → HTTP ${response.code}，${text.length} 字符",
                )
                if (!response.isSuccessful) {
                    throw AiException("HTTP ${response.code}：${text.take(300)}", response.code)
                }
                text
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            debugLog?.invoke("请求异常 ${request.method} ${request.url.encodedPath}：${e.javaClass.simpleName}：${e.message}")
            throw AiNetworkException(e)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        /** 回复 token 上限:要留够推理模型的思考空间(思考也算 token) */
        private const val MAX_TOKENS = 4096

        /**
         * 探针用 64×64 纯红 PNG(RGBA, 332 字符)。
         * 注意:不能用「1×1 灰度+透明(colortype 4)」那类极简 PNG——部分后端(如 SenseNova)
         * 会直接报 400 invalid image base64 content;标准 RGBA PNG 才稳。
         */
        const val PROBE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACMSURBVHhe7dAhAQAADITA7196K0ADEGeQ7LYzawBFkwZQNGkARZMGUDRpAEWTBlA0aQBFkwZQNGkARZMGUDRpAEWTBlA0aQBFkwZQNGkARZMGUDRpAEWTBlA0aQBFkwZQNGkARZMGUDRpAEWTBlA0aQBFkwZQNGkARZMGUDRpAEWTBlA0aQBFE/mA3QNDiOHS/086WQAAAABJRU5ErkJggg=="

        private const val PROBE_PROMPT = "这张图片是什么颜色？只回一个词。"

        /**
         * 地址归一化:无 scheme 补 https://,去尾部斜杠。
         * 约定:填 OpenAI 兼容 API 的 base(含 /v1);端点 = base + /models、base + /chat/completions。
         */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.trimEnd('/')
        }
    }
}

/** 本文件私有:协程等待 OkHttp 回调(与 OpenListClient 同构,文件级私有不冲突) */
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
