package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.OpenListApiException
import com.tomcat927.miscuploader.core.OpenListClient
import java.io.File
import java.net.URLDecoder
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
 * 协议回归测试(拍板:质量门必须能挡住协议级回归;对应 client-protocol-decision.md 防御清单)。
 * 全部走 MockWebServer,CI 与任何真实服务器零耦合。
 */
class OpenListClientTest {

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

    private fun newClient(): OpenListClient =
        OpenListClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "user",
            password = "pass",
            baseClient = OkHttpClient(),
        )

    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"message":"","data":{"token":"tk-1"}}""")

    private fun listOk() = MockResponse().setBody(
        """{"code":200,"message":"","data":{"content":""" +
            """[{"name":"a.png","size":10,"is_dir":false,"modified":"2026-10-03T10:00:00+08:00"},""" +
            """{"name":"docs","size":0,"is_dir":true}],"total":2}}""",
    )

    private fun putOk() = MockResponse().setBody("""{"code":200,"message":"success","data":null}""")

    @Test
    fun `login then list sends token and parses entries`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(listOk())
        val client = newClient()
        client.login()
        val entries = client.list("/")

        assertEquals(2, entries.size)
        assertEquals("a.png", entries[0].name)
        assertEquals(10L, entries[0].size)
        assertEquals(false, entries[0].isDir)
        assertEquals("docs", entries[1].name)
        assertTrue(entries[1].isDir)

        val loginReq = server.takeRequest()
        assertEquals("/api/auth/login", loginReq.path)
        val loginBody = loginReq.body.readUtf8()
        assertTrue(loginBody.contains("\"username\":\"user\""))
        assertTrue(loginBody.contains("\"password\":\"pass\""))

        val listReq = server.takeRequest()
        assertEquals("/api/fs/list", listReq.path)
        assertEquals("tk-1", listReq.getHeader("Authorization"))
        val listBody = listReq.body.readUtf8()
        assertTrue(listBody.contains("\"path\":\"/\""))
        assertTrue(listBody.contains("\"per_page\":1000"))
        assertTrue(listBody.contains("\"refresh\":false"))
    }

    @Test
    fun `login with wrong credentials throws api exception not fake success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":401,"message":"wrong password"}"""))
        try {
            newClient().login()
            fail("expected OpenListApiException")
        } catch (e: OpenListApiException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("wrong password"))
        }
    }

    @Test
    fun `list on 401 relogins once and retries with fresh token`() = runTest {
        server.enqueue(loginOk())                          // 初始登录
        server.enqueue(MockResponse().setResponseCode(401)) // list 过期
        server.enqueue(loginOk())                          // 重登
        server.enqueue(listOk())                           // 重试
        val client = newClient()
        client.login()

        val entries = client.list("/")
        assertEquals(2, entries.size)

        server.takeRequest() // login
        server.takeRequest() // list → 401
        server.takeRequest() // re-login
        val retried = server.takeRequest()
        assertEquals("tk-1", retried.getHeader("Authorization"))
        assertEquals("1", retried.getHeader("X-Misc-Token-Retry"))
    }

    @Test
    fun `list on repeated 401 gives up without infinite loop`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setResponseCode(401)) // list 过期
        server.enqueue(loginOk())                          // 重登
        server.enqueue(MockResponse().setResponseCode(401)) // 重试仍 401 → 不再重试
        val client = newClient()
        client.login()

        try {
            client.list("/")
            fail("expected failure")
        } catch (e: OpenListApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `mkdir posts path and requires code 200`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"","data":null}"""))
        val client = newClient()
        client.login()
        client.mkdir("/misc/newdir")

        server.takeRequest()
        val mkdirReq = server.takeRequest()
        assertEquals("/api/fs/mkdir", mkdirReq.path)
        assertTrue(mkdirReq.body.readUtf8().contains("\"path\":\"/misc/newdir\""))
    }

    @Test
    fun `upload streams file, encodes file path, reports progress`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(putOk())
        val client = newClient()
        client.login()

        val payload = ByteArray(200_000) { (it % 7).toByte() }
        val tmp = File.createTempFile("misc-test", ".bin").apply { writeBytes(payload) }
        val progresses = mutableListOf<Pair<Long, Long>>()

        client.upload(tmp, "/misc/测试目录/图 片.png", overwrite = true) { sent, total ->
            progresses.add(sent to total)
        }

        assertEquals(200_000L, progresses.last().first)
        assertEquals(200_000L, progresses.last().second)

        server.takeRequest()
        val putReq = server.takeRequest()
        assertEquals("/api/fs/put", putReq.path)
        val filePath = putReq.getHeader("File-Path")!!
        assertEquals(
            "/misc/测试目录/图 片.png",
            URLDecoder.decode(filePath, "UTF-8").replace("+", " "),
        )
        assertEquals("true", putReq.getHeader("Overwrite"))
        assertEquals(200_000L, putReq.bodySize)
        tmp.delete()
    }

    @Test
    fun `upload with overwrite false sends false header`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(putOk())
        val client = newClient()
        client.login()
        val tmp = File.createTempFile("misc-test", ".bin").apply { writeBytes(ByteArray(10)) }
        client.upload(tmp, "/a.png", overwrite = false)

        server.takeRequest()
        assertEquals("false", server.takeRequest().getHeader("Overwrite"))
        tmp.delete()
    }

    @Test
    fun `upload html body with http 200 fails as fake success`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("<html>index.html fallback</html>"))
        val client = newClient()
        client.login()
        val tmp = File.createTempFile("misc-test", ".bin").apply { writeBytes(ByteArray(10)) }

        try {
            client.upload(tmp, "/a.png")
            fail("expected OpenListApiException for non-JSON 200")
        } catch (e: OpenListApiException) {
            assertTrue(e.message!!.contains("不是 JSON"))
        }
        tmp.delete()
    }

    @Test
    fun `upload with server 403 exists gate surfaces code`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"code":403,"message":"file exists"}"""))
        val client = newClient()
        client.login()
        val tmp = File.createTempFile("misc-test", ".bin").apply { writeBytes(ByteArray(10)) }

        try {
            client.upload(tmp, "/a.png")
            fail("expected OpenListApiException 403")
        } catch (e: OpenListApiException) {
            assertEquals(403, e.code)
        }
        tmp.delete()
    }

    @Test
    fun `move and remove send snake_case bodies to correct routes`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(putOk()) // move
        server.enqueue(putOk()) // remove
        val client = newClient()
        client.login()

        client.move(srcDir = "/auto/2026/10", names = listOf("a.png", "docs"), dstDir = "/docs")
        client.remove(dir = "/docs", names = listOf("a.png"))

        server.takeRequest() // login
        val moveReq = server.takeRequest()
        assertEquals("/api/fs/move", moveReq.path)
        val moveBody = moveReq.body.readUtf8()
        assertTrue(moveBody.contains("\"src_dir\":\"/auto/2026/10\""))
        assertTrue(moveBody.contains("\"dst_dir\":\"/docs\""))
        assertTrue(moveBody.contains("\"names\":[\"a.png\",\"docs\"]"))

        val removeReq = server.takeRequest()
        assertEquals("/api/fs/remove", removeReq.path)
        val removeBody = removeReq.body.readUtf8()
        assertTrue(removeBody.contains("\"dir\":\"/docs\""))
        assertTrue(removeBody.contains("\"names\":[\"a.png\"]"))
    }

    @Test
    fun `move with permission denied surfaces code 403`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"code":403,"message":"permission denied"}"""))
        val client = newClient()
        client.login()

        try {
            client.move("/", listOf("x.png"), "/docs")
            fail("expected OpenListApiException 403")
        } catch (e: OpenListApiException) {
            assertEquals(403, e.code)
        }
    }
}
