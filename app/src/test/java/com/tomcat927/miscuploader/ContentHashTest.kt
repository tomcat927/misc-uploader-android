package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.ContentHash
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A1 去重依据的回归:流式分块 hash 必须与标准结果/整体一次性 hash 一致 */
class ContentHashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty file matches standard sha256 vector`() {
        val f = tmp.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.sha256(f),
        )
    }

    @Test
    fun `abc matches standard sha256 vector`() {
        val f = tmp.newFile("abc.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.sha256(f),
        )
    }

    @Test
    fun `multi chunk streaming equals single shot digest across 64k boundary`() {
        // 200KB 跨多个 64KB 分块,验证流式 read 循环不丢块不加块
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val f = tmp.newFile("big.bin").apply { writeBytes(data) }
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ContentHash.sha256(f))
    }
}
