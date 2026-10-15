package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.AiClient
import com.tomcat927.miscuploader.core.AiException
import kotlinx.coroutines.test.runTest
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
        val out = newClient().chatVision("gpt-4o", "什么颜色", "AAAA", "image/png")

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
}
