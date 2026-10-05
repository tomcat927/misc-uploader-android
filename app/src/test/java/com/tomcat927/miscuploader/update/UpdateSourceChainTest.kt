package com.tomcat927.miscuploader.update

import com.tomcat927.miscuploader.data.UpdateSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新源偏好源链裁剪(拍板 2026-10-06):纯函数,防偏好逻辑回归 */
class UpdateSourceChainTest {

    private val proxyManifest = "https://gh-proxy.com/https://github.com/tomcat927/misc-uploader-android/releases/latest/download/latest.json"
    private val directManifest = "https://github.com/tomcat927/misc-uploader-android/releases/latest/download/latest.json"

    @Test
    fun `清单源链 - 三种偏好`() {
        assertEquals(listOf(proxyManifest, directManifest), UpdateService.manifestChainFor(UpdateSourcePreference.AUTO))
        assertEquals(listOf(directManifest), UpdateService.manifestChainFor(UpdateSourcePreference.GITHUB))
        assertEquals(listOf(proxyManifest), UpdateService.manifestChainFor(UpdateSourcePreference.GHPROXY))
    }

    @Test
    fun `下载对 - 偏好约束镜像使用范围`() {
        val (autoFirst, autoFallback) = UpdateService.downloadPairFor(UpdateSourcePreference.AUTO, "mirror", "direct")
        assertEquals("mirror", autoFirst)
        assertEquals("direct", autoFallback)

        val (ghFirst, ghFallback) = UpdateService.downloadPairFor(UpdateSourcePreference.GITHUB, "mirror", "direct")
        assertEquals("direct", ghFirst)
        assertEquals("direct", ghFallback)

        val (pxFirst, pxFallback) = UpdateService.downloadPairFor(UpdateSourcePreference.GHPROXY, "mirror", "direct")
        assertEquals("mirror", pxFirst)
        assertEquals("mirror", pxFallback)
    }

    @Test
    fun `API 兜底 - 仅镜像偏好下不使用`() {
        assertTrue(UpdateService.apiFallbackFor(UpdateSourcePreference.AUTO))
        assertTrue(UpdateService.apiFallbackFor(UpdateSourcePreference.GITHUB))
        assertFalse(UpdateService.apiFallbackFor(UpdateSourcePreference.GHPROXY))
    }
}
