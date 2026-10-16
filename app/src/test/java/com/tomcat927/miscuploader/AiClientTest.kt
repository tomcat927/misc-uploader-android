package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.AiClient
import com.tomcat927.miscuploader.core.AiException
import com.tomcat927.miscuploader.core.AiImage
import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.core.AiTool
import com.tomcat927.miscuploader.core.AiToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * AI 助手(OpenAI 兼容)协议回归测试:全走 MockWebServer,与真实服务零耦合。
 */
class AiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(): AiClient = AiClient(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        baseClient = OkHttpClient(),
    )

    @Test
    fun `listModels sends bearer and parses ids sorted with blanks dropped`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"claude-3"},{"id":""}]}"""),
        )
        val models = newClient().listModels()

        assertEquals(listOf("claude-3", "gpt-4o"), models)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test
    fun `chatVision posts image_url data uri and parses content`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"白色"}}]}"""),
        )
        val out = newClient().chatVision("gpt-4o", "什么颜色", listOf(AiImage("AAAA", "image/png")))

        assertEquals("白色", out)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o\""))
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `verifyVision uses the embedded probe png`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"透明"}}]}"""))
        newClient().verifyVision("gpt-4o")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(AiClient.PROBE_PNG_BASE64))
        assertTrue(body.contains("data:image/png;base64,"))
    }

    @Test
    fun `content as array of parts is concatenated`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":[{"type":"text","text":"ab"},{"type":"text","text":"cd"}]}}]}""",
            ),
        )
        assertEquals("abcd", newClient().chatText("m", "hi"))
    }

    @Test
    fun `non 2xx surfaces AiException with http code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            newClient().listModels()
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `missing choices surfaces AiException`() = runTest {
        server.enqueue(MockResponse().setBody("""{"object":"x"}"""))
        try {
            newClient().chatText("m", "hi")
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("choices"))
        }
    }

    @Test
    fun `null content falls back to reasoning_content`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"/图片/杂乱的图片"}}]}""",
            ),
        )
        assertEquals("/图片/杂乱的图片", newClient().chatText("m", "hi"))
    }

    @Test
    fun `absent content key falls back to reasoning_content`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","reasoning_content":"/ai"}}]}""",
            ),
        )
        assertEquals("/ai", newClient().chatText("m", "hi"))
    }

    @Test
    fun `legacy choices text is accepted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"text":"/docs"}]}"""))
        assertEquals("/docs", newClient().chatText("m", "hi"))
    }

    @Test
    fun `message without any text surfaces AiException`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant"}}]}"""))
        try {
            newClient().chatText("m", "hi")
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("message.content"))
        }
    }

    // ---- 工具调用(agent) ----

    private fun probeTool(): AiTool = AiTool(
        name = "list_local",
        description = "列出目录",
        parameters = buildJsonObject { put("type", "object") },
    )

    @Test
    fun `chatWithTools posts tools and parses tool_calls`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,""" +
                    """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"list_local","arguments":"{\"path\":\"/sdcard\"}"}}]},""" +
                    """"finish_reason":"tool_calls"}]}""",
            ),
        )
        val out = newClient().chatWithTools("m", listOf(AiMessage("user", "看看下载目录")), listOf(probeTool()))

        assertEquals(1, out.toolCalls.size)
        assertEquals("call_1", out.toolCalls[0].id)
        assertEquals("list_local", out.toolCalls[0].name)
        assertEquals("{\"path\":\"/sdcard\"}", out.toolCalls[0].arguments)
        assertEquals("tool_calls", out.finishReason)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
        assertTrue(body.contains("\"name\":\"list_local\""))
    }

    @Test
    fun `chatWithTools accepts plain text reply without tools`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"完成"}}]}"""))
        val out = newClient().chatWithTools("m", listOf(AiMessage("user", "hi")), listOf(probeTool()))

        assertEquals("完成", out.text)
        assertTrue(out.toolCalls.isEmpty())
    }

    @Test
    fun `tool role message carries tool_call_id and assistant tool_calls serialized`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好的"}}]}"""))
        val history = listOf(
            AiMessage("user", "看看下载目录"),
            AiMessage(
                "assistant",
                toolCalls = listOf(AiToolCall("call_1", "list_local", "{\"path\":\"/sdcard\"}")),
            ),
            AiMessage("tool", text = "12 项", toolCallId = "call_1"),
        )
        newClient().chatWithTools("m", history, listOf(probeTool()))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"role\":\"tool\""))
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(body.contains("\"tool_calls\""))
        assertTrue(body.contains("\"arguments\":\"{\\\"path\\\":\\\"/sdcard\\\"}\""))
        assertTrue(body.contains("12 项"))
    }

    @Test
    fun `probeTools true when tool call returned`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"tool_calls":[{"id":"c","type":"function","function":{"name":"noop_probe","arguments":"{\"ping\":\"pong\"}"}}]}}]}""",
            ),
        )
        assertTrue(newClient().probeTools("m"))
    }

    @Test
    fun `probeTools false when only text returned`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"不支持"}}]}"""))
        assertTrue(!newClient().probeTools("m"))
    }

    @Test
    fun `empty content and no tool_calls surfaces AiException`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant"}}]}"""))
        try {
            newClient().chatWithTools("m", listOf(AiMessage("user", "hi")), listOf(probeTool()))
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("message.content"))
        }
    }
}
