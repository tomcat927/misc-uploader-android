package com.tomcat927.miscuploader.data

import java.text.SimpleDateFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LsParserTest {

    private val fallback = 1700000000000L

    @Test
    fun `toybox 文件行 - 名字含空格与日期解析`() {
        val entry = LsParser.parseLsLine(
            "-rw-rw---- 1 u0_a321 everyone 12345 2026-10-05 12:34 my download file.apk",
            fallback,
        )
        assertNotNull(entry)
        entry!!.let {
            assertEquals("my download file.apk", it.name)
            assertFalse(it.isDir)
            assertEquals(12345L, it.size)
            // 与解析器同用默认时区,期望值按同一格式现算
            assertEquals(
                SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-10-05 12:34")!!.time,
                it.mtimeMs,
            )
        }
    }

    @Test
    fun `toybox 目录行 - isDir 为真`() {
        val entry = LsParser.parseLsLine(
            "drwxrwx--x 2 u0_a321 everyone 4096 2026-09-01 08:00 subtitles",
            fallback,
        )
        assertNotNull(entry)
        assertTrue(entry!!.isDir)
        assertEquals("subtitles", entry.name)
        assertEquals(4096L, entry.size)
    }

    @Test
    fun `symlink 行 - 展示名截掉箭头尾巴且按文件处理`() {
        val entry = LsParser.parseLsLine(
            "lrwxrwxrwx 1 root root 25 2026-10-05 09:00 latest.bin -> /data/other/latest.bin",
            fallback,
        )
        assertNotNull(entry)
        assertEquals("latest.bin", entry!!.name)
        assertFalse(entry.isDir)
    }

    @Test
    fun `GNU 列序行 - 整行丢弃不产出垃圾条目`() {
        val entry = LsParser.parseLsLine(
            "-rw-r--r-- 1 root root 7 Oct 5 2026 weird.txt",
            fallback,
        )
        // GNU 列序 date 占位不同 → 日期段形状校验不过,整行不可信
        assertNull(entry)
    }

    @Test
    fun `日期形状对但值非法 - mtime 落回 fallback`() {
        val entry = LsParser.parseLsLine(
            "-rw-r--r-- 1 root root 7 2026-13-45 99:99 ok.txt",
            fallback,
        )
        assertNotNull(entry)
        assertEquals("ok.txt", entry!!.name)
        assertEquals(fallback, entry.mtimeMs)
    }

    @Test
    fun `total 行与短行与垃圾行 - 返回 null`() {
        assertNull(LsParser.parseLsLine("total 24", fallback))
        assertNull(LsParser.parseLsLine("", fallback))
        assertNull(LsParser.parseLsLine("abc def", fallback))
        assertNull(
            LsParser.parseLsLine("-rw-r--r-- 1 root root 7 2026-10-05 12:00", fallback),
        )
    }

    @Test
    fun `stat 行 - size mtime 完整路径含空格`() {
        val entry = LsParser.parseStatLine("12345 1728123456 /storage/emulated/0/Android/data/x/files/a b.apk")
        assertNotNull(entry)
        entry!!.let {
            assertEquals(12345L, it.size)
            assertEquals(1728123456000L, it.mtimeMs)
            assertEquals("/storage/emulated/0/Android/data/x/files/a b.apk", it.path)
            assertEquals("a b.apk", it.name)
            assertFalse(it.isDir)
        }
    }

    @Test
    fun `stat 行残缺 - 返回 null`() {
        assertNull(LsParser.parseStatLine("12345 1728123456"))
        assertNull(LsParser.parseStatLine("abc def /path"))
        assertNull(LsParser.parseStatLine(""))
    }
}
