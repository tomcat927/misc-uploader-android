package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.GalleryLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D1 深链格式回归(格式对照 pigallery2 app.routing.ts galleryMatcher) */
class GalleryLinkTest {

    @Test
    fun `blank base returns null`() {
        assertNull(GalleryLink.forDir(null, "/auto"))
        assertNull(GalleryLink.forDir("", "/auto"))
        assertNull(GalleryLink.forDir("   ", "/auto"))
    }

    @Test
    fun `root dir opens gallery home`() {
        assertEquals("https://g.example.com/gallery", GalleryLink.forDir("https://g.example.com", "/"))
        assertEquals("https://g.example.com/gallery", GalleryLink.forDir("https://g.example.com/", ""))
    }

    @Test
    fun `nested dir joins with percent2F as single route segment`() {
        assertEquals(
            "https://g.example.com/gallery/auto%2F2026%2F10",
            GalleryLink.forDir("https://g.example.com", "/auto/2026/10"),
        )
    }

    @Test
    fun `base with trailing gallery is not doubled`() {
        assertEquals(
            "https://g.example.com/gallery/auto",
            GalleryLink.forDir("https://g.example.com/gallery", "/auto"),
        )
        assertEquals(
            "https://g.example.com/gallery/auto",
            GalleryLink.forDir("https://g.example.com/gallery/", "/auto"),
        )
    }

    @Test
    fun `spaces and chinese are percent encoded inside segment`() {
        assertEquals(
            "https://g.example.com/gallery/%E7%85%A7%E7%89%87%202026",
            GalleryLink.forDir("https://g.example.com", "/照片 2026"),
        )
    }
}
