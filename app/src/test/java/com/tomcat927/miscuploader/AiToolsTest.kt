package com.tomcat927.miscuploader

import com.tomcat927.miscuploader.core.AiTools
import com.tomcat927.miscuploader.core.optBool
import com.tomcat927.miscuploader.core.optInt
import com.tomcat927.miscuploader.core.optString
import com.tomcat927.miscuploader.core.optStringList
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 工具注册表: schema 合法性 + 参数安全解析(纯函数) */
class AiToolsTest {

    @Test
    fun `every tool has non-blank name and description`() {
        assertTrue(AiTools.all.isNotEmpty())
        AiTools.all.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.tool.name == it.name)
            assertTrue(it.tool.description.isNotBlank())
        }
    }

    @Test
    fun `tool names are unique`() {
        val names = AiTools.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every parameters is an object schema`() {
        AiTools.all.forEach { spec ->
            val params = spec.tool.parameters
            assertEquals("object", params["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `action tools are exactly enqueue_upload and trash_local`() {
        val actions = AiTools.all.filter { it.kind == AiTools.Kind.ACTION }.map { it.name }.toSet()
        assertEquals(setOf(AiTools.ENQUEUE_UPLOAD, AiTools.TRASH_LOCAL), actions)
        assertEquals(
            AiTools.all.size - 2,
            AiTools.all.count { it.kind == AiTools.Kind.READ_ONLY },
        )
    }

    @Test
    fun `enqueue_upload requires items and target_dir`() {
        val params = AiTools.spec(AiTools.ENQUEUE_UPLOAD)!!.tool.parameters
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf(AiTools.ARG_ITEMS, AiTools.ARG_TARGET_DIR), required)
    }

    @Test
    fun `cleanupAfter maps modes to upload flags`() {
        assertEquals(0, AiTools.cleanupAfter(AiTools.CLEANUP_NONE))
        assertEquals(1, AiTools.cleanupAfter(AiTools.CLEANUP_PENDING))
        assertEquals(2, AiTools.cleanupAfter(AiTools.CLEANUP_IMMEDIATE))
        assertEquals(1, AiTools.cleanupAfter(null))
        assertEquals(1, AiTools.cleanupAfter("garbage"))
    }

    @Test
    fun `parseArgs tolerates invalid json`() {
        assertTrue(AiTools.parseArgs("not json").isEmpty())
        assertTrue(AiTools.parseArgs("").isEmpty())
        assertEquals("x", AiTools.parseArgs("""{"a":"x"}""")["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `optString returns null for blank missing or non-primitive`() {
        val args = AiTools.parseArgs("""{"p":"/sdcard","blank":"  ","num":3,"arr":["x"]}""")
        assertEquals("/sdcard", args.optString("p"))
        assertNull(args.optString("blank"))
        assertNull(args.optString("missing"))
        assertNull(args.optString("arr"))
    }

    @Test
    fun `optStringList keeps only non-blank primitives`() {
        val args = AiTools.parseArgs("""{"paths":["/a","  ","/b",5]}""")
        assertEquals(listOf("/a", "/b", "5"), args.optStringList("paths"))
        assertTrue(AiTools.parseArgs("""{"paths":"not-array"}""").optStringList("paths").isEmpty())
    }

    @Test
    fun `optInt and optBool fall back to defaults`() {
        val args = AiTools.parseArgs("""{"d":"3","r":true}""")
        assertEquals(3, args.optInt("d", 2))
        assertEquals(2, args.optInt("missing", 2))
        assertEquals(2, args.optInt("arr", 2))
        assertTrue(args.optBool("r", false))
        assertTrue(args.optBool("missing", true))
    }

    @Test
    fun `toolDefs exposes all specs`() {
        assertEquals(AiTools.all.size, AiTools.toolDefs().size)
        assertNotNull(AiTools.spec(AiTools.LIST_LOCAL))
        assertNull(AiTools.spec("nonexistent"))
    }

    @Test
    fun `noop object helper builds properties map`() {
        // 无参 schema 的 properties 为空对象
        val empty = AiTools.spec(AiTools.LIST_PENDING_CLEANUP)!!.tool.parameters
        assertTrue(empty["properties"]!!.jsonObject.isEmpty())
    }
}
