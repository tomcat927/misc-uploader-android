package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.data.AiSuggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 落点建议的纯逻辑单测:提示词拼装 + 回复解析(不依赖网络/Android)。
 */
class AiSuggestTest {

    @Test
    fun `parseDir extracts path and strips trailing punctuation`() {
        assertEquals("/图片", AiSuggest.parseDir("/图片", "/"))
        assertEquals("/图片", AiSuggest.parseDir("这些文件建议放到 /图片 目录。", "/"))
        assertEquals("/docs/发票", AiSuggest.parseDir("/docs/发票。", "/"))
        assertEquals("/图片", AiSuggest.parseDir("```\n/图片\n```", "/"))
    }

    @Test
    fun `parseDir normalizes trailing slash and leading`() {
        assertEquals("/auto/2026/10", AiSuggest.parseDir("/auto/2026/10/", "/"))
        assertEquals("/docs", AiSuggest.parseDir("docs", "/"))
    }

    @Test
    fun `parseDir falls back when no path present`() {
        assertEquals("/cur", AiSuggest.parseDir("我不确定该放哪", "/cur"))
        assertEquals("/cur", AiSuggest.parseDir("", "/cur"))
    }

    @Test
    fun `prompt lists dirs current dir and files`() {
        val p = AiSuggest.prompt(listOf("图片", "docs"), "/", listOf("IMG_1.jpg"))
        assertTrue(p.contains("图片"))
        assertTrue(p.contains("当前所在目录：/"))
        assertTrue(p.contains("- IMG_1.jpg"))
    }
}
