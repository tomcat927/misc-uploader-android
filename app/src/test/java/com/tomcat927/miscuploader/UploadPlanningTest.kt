package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.data.UploadPlanning
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A2 自动归类目录规划(时区固定为 Asia/Shanghai,与用户场景一致) */
class UploadPlanningTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    private fun msOf(year: Int, month1based: Int, day: Int): Long =
        Calendar.getInstance().apply { set(year, month1based - 1, day, 13, 0, 0) }.timeInMillis

    @Test
    fun `autoDirFor uses file mtime year and month`() {
        assertEquals("auto/2026/10", UploadPlanning.autoDirFor(msOf(2026, 10, 5)))
        assertEquals("auto/2025/01", UploadPlanning.autoDirFor(msOf(2025, 1, 31)))
    }

    @Test
    fun `joinRemotePath handles root and nested and trailing slash`() {
        assertEquals("/a.png", UploadPlanning.joinRemotePath("/", "a.png"))
        assertEquals("/misc/sub/a.png", UploadPlanning.joinRemotePath("/misc/sub", "a.png"))
        assertEquals("/misc/a.png", UploadPlanning.joinRemotePath("/misc/", "a.png"))
        assertEquals("/auto/2026/10/a.png", UploadPlanning.joinRemotePath("auto/2026/10", "a.png"))
    }
}
