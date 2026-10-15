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
    fun `parseDir normalizes trailing slash`() {
        assertEquals("/auto/2026/10", AiSuggest.parseDir("/auto/2026/10/", "/"))
        assertEquals("/docs", AiSuggest.parseDir("放到 /docs/ 里", "/"))
    }

    @Test
    fun `parseDir falls back when no path present`() {
        assertEquals("/cur", AiSuggest.parseDir("我不确定该放哪", "/cur"))
        assertEquals("/cur", AiSuggest.parseDir("", "/cur"))
    }

    @Test
    fun `parseCleanup keeps only known names and extracts reasons`() {
        val avail = setOf("IMG_1.jpg", "cache.tmp", "报告.pdf")
        val resp = """
            IMG_1.jpg	重复截图
            - cache.tmp - 临时文件
            「报告.pdf」：重要文档（不该出现）
            编造.txt	不存在
        """.trimIndent()
        val out = AiSuggest.parseCleanup(resp, avail)
        assertEquals(listOf("IMG_1.jpg", "cache.tmp", "报告.pdf"), out.map { it.first })
        assertEquals("重复截图", out[0].second)
        assertEquals("临时文件", out[1].second)
        assertEquals("重要文档（不该出现）", out[2].second)
    }

    @Test
    fun `parseCleanup prefers longest name match and dedups`() {
        val avail = setOf("a.jpg", "a.jpg.bak")
        val out = AiSuggest.parseCleanup("a.jpg.bak\t冗余备份", avail)
        assertEquals(listOf("a.jpg.bak" to "冗余备份"), out)
        assertEquals(1, AiSuggest.parseCleanup("a.jpg.bak\tx\na.jpg.bak\ty", avail).size)
    }

    @Test
    fun `parseCleanup empty available returns empty`() {
        assertTrue(AiSuggest.parseCleanup("x\t y", emptySet()).isEmpty())
    }

    @Test
    fun `prompt lists dirs current dir and files`() {
        val p = AiSuggest.prompt(listOf("图片", "docs"), "/", listOf("IMG_1.jpg"))
        assertTrue(p.contains("图片"))
        assertTrue(p.contains("当前所在目录：/"))
        assertTrue(p.contains("- IMG_1.jpg"))
    }
}
