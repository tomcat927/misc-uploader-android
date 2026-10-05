package com.tomcat927.miscuploader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchingTest {

    @Test
    fun `空词匹配一切`() {
        assertTrue(NameMatching.matches("", "anything.txt"))
        assertTrue(NameMatching.matches("   ", "anything.txt"))
    }

    @Test
    fun `无通配符 - 忽略大小写子串包含`() {
        assertTrue(NameMatching.matches("apk", "my_app_backup.bin"))
        assertTrue(NameMatching.matches("APK", "my_app_backup.bin"))
        assertTrue(NameMatching.matches("下载", "浏览器下载文件.mp4"))
        assertFalse(NameMatching.matches("xyz", "my_app_backup.bin"))
    }

    @Test
    fun `星号通配 - 整名匹配而非包含`() {
        assertTrue(NameMatching.matches("*.apk", "app.apk"))
        assertTrue(NameMatching.matches("*.APK", "app.apk"))
        assertFalse(NameMatching.matches("*.apk", "app.zip"))
        assertFalse(NameMatching.matches("*.apk", "dir/app.apk")) // 整名匹配,斜杠不内含
        assertTrue(NameMatching.matches("*download*", "My Download File.apk"))
    }

    @Test
    fun `问号通配 - 单字符占位`() {
        assertTrue(NameMatching.matches("img_???.png", "img_001.png"))
        assertFalse(NameMatching.matches("img_???.png", "img_01.png"))
        assertTrue(NameMatching.matches("img_????.png", "img_0001.png"))
    }

    @Test
    fun `正则元字符按字面处理`() {
        // + 在正则是量词,这里必须按字面匹配
        assertTrue(NameMatching.matches("a+b.*", "a+b.apk"))
        assertFalse(NameMatching.matches("a+b.*", "aab.apk"))
        // 点号不被当任意字符
        assertTrue(NameMatching.matches("v1.0*", "v1.0_release.zip"))
        assertFalse(NameMatching.matches("v1?0", "v1x0_release.zip"))
    }

    @Test
    fun `混合通配符与忽略大小写`() {
        assertTrue(NameMatching.matches("*Screen?hot*", "screenshot_20261005.png"))
        assertTrue(NameMatching.matches("*.??$", "price.us$"))
    }
}

class SearchTimeRangeTest {

    @Test
    fun `不限 - 不过滤`() {
        assertEquals(null, SearchTimeRange.ANY.sinceMs(1_000_000L))
    }

    @Test
    fun `近 N 天 - 按毫秒回溯`() {
        val now = 1_728_123_456_789L
        assertEquals(now - 7L * 86_400_000, SearchTimeRange.WEEK.sinceMs(now))
        assertEquals(now - 30L * 86_400_000, SearchTimeRange.MONTH.sinceMs(now))
    }

    @Test
    fun `今天 - 本地零点起且落在合理区间`() {
        val now = System.currentTimeMillis()
        val since = SearchTimeRange.TODAY.sinceMs(now)!!
        // 本地零点必然在 [now-24h, now] 区间内(跨午夜边界以外恒成立)
        assertTrue(since <= now)
        assertTrue(since > now - 86_400_000)
    }
}
