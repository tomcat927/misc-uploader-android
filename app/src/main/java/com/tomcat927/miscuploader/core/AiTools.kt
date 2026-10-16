package com.tomcat927.miscuploader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * AI agent 工具注册表:名称/描述/JSON Schema/敏感级别。
 * 纯定义 + 纯参数解析,无副作用(执行在 data/AiAgent),可单测。
 *
 * 分档(拍板 2026-10-16):[Kind.READ_ONLY] 只读 → 后台自动执行;
 * [Kind.ACTION] 动作 → 必须人工确认后才执行。
 */
object AiTools {

    enum class Kind { READ_ONLY, ACTION }

    /** 工具 + 其敏感级别 */
    data class Spec(val name: String, val kind: Kind, val tool: AiTool)

    // ---- 名称 ----
    const val LIST_LOCAL = "list_local"
    const val READ_IMAGES = "read_images"
    const val LIST_REPO = "list_repo"
    const val REPO_TREE = "repo_tree"
    const val SEARCH_LOCAL = "search_local"
    const val LIST_PENDING_CLEANUP = "list_pending_cleanup"
    const val ENQUEUE_UPLOAD = "enqueue_upload"
    const val TRASH_LOCAL = "trash_local"

    // ---- 参数键 ----
    const val ARG_PATH = "path"
    const val ARG_PATHS = "paths"
    const val ARG_QUERY = "query"
    const val ARG_RECURSIVE = "recursive"
    const val ARG_DEPTH = "depth"
    const val ARG_ITEMS = "items"
    const val ARG_TARGET_DIR = "target_dir"
    const val ARG_CLEANUP_MODE = "cleanup_mode"

    /** cleanup_mode 取值(映射 upload_items.cleanupAfter:none=0 / pending=1 / immediate=2) */
    const val CLEANUP_NONE = "none"
    const val CLEANUP_PENDING = "pending"
    const val CLEANUP_IMMEDIATE = "immediate"

    fun cleanupAfter(mode: String?): Int = when (mode) {
        CLEANUP_IMMEDIATE -> 2
        CLEANUP_NONE -> 0
        else -> 1
    }

    private fun tool(name: String, description: String, parameters: JsonObject) =
        AiTool(name = name, description = description, parameters = parameters)

    val all: List<Spec> = listOf(
        Spec(
            LIST_LOCAL,
            Kind.READ_ONLY,
            tool(
                LIST_LOCAL,
                "列出手机本地某个目录下的条目(名称/大小/是否目录)。path 为绝对路径。",
                obj(ARG_PATH to str("目录绝对路径,如 /sdcard/Download"), required = listOf(ARG_PATH)),
            ),
        ),
        Spec(
            READ_IMAGES,
            Kind.READ_ONLY,
            tool(
                READ_IMAGES,
                "读取若干本地图片文件,把缩略图发给模型用于识别内容(仅图片格式有效)。",
                obj(ARG_PATHS to strArray("图片绝对路径列表"), required = listOf(ARG_PATHS)),
            ),
        ),
        Spec(
            LIST_REPO,
            Kind.READ_ONLY,
            tool(
                LIST_REPO,
                "列出 OpenList 仓库某个远程目录下的条目。path 为仓库内路径(以 / 开头,根为 /)。",
                obj(ARG_PATH to str("仓库内路径,如 / 或 /图片"), required = listOf(ARG_PATH)),
            ),
        ),
        Spec(
            REPO_TREE,
            Kind.READ_ONLY,
            tool(
                REPO_TREE,
                "读取仓库目录树(用于判断归档落点),返回若干层内的所有目录路径。",
                obj(ARG_DEPTH to int("递归层数,默认 2,最大 4")),
            ),
        ),
        Spec(
            SEARCH_LOCAL,
            Kind.READ_ONLY,
            tool(
                SEARCH_LOCAL,
                "在本地某目录下按名称搜索文件(支持 * 与 ? 通配,大小写不敏感)。",
                obj(
                    ARG_QUERY to str("搜索关键词,支持 * 与 ? 通配"),
                    ARG_PATH to str("起始目录绝对路径,可省略(默认当前目录)"),
                    ARG_RECURSIVE to bool("是否递归子目录,默认 true"),
                    required = listOf(ARG_QUERY),
                ),
            ),
        ),
        Spec(
            LIST_PENDING_CLEANUP,
            Kind.READ_ONLY,
            tool(
                LIST_PENDING_CLEANUP,
                "列出「待清理列表」中的本地文件(已归档、待用户决定是否删除)。无参数。",
                obj(),
            ),
        ),
        Spec(
            ENQUEUE_UPLOAD,
            Kind.ACTION,
            tool(
                ENQUEUE_UPLOAD,
                "把选中的本地文件/文件夹加入上传队列(上传到仓库 target_dir)。" +
                    "cleanup_mode 决定上传完成后对本地原件的处理:none=不管 / pending=进待清理列表 / immediate=立即移入回收站。" +
                    "此操作需用户确认。",
                obj(
                    ARG_ITEMS to strArray("本地文件或文件夹的绝对路径列表"),
                    ARG_TARGET_DIR to str("仓库内目标目录,以 / 开头"),
                    ARG_CLEANUP_MODE to enumStr(
                        "上传完成后本地原件处理方式",
                        listOf(CLEANUP_NONE, CLEANUP_PENDING, CLEANUP_IMMEDIATE),
                    ),
                    required = listOf(ARG_ITEMS, ARG_TARGET_DIR),
                ),
            ),
        ),
        Spec(
            TRASH_LOCAL,
            Kind.ACTION,
            tool(
                TRASH_LOCAL,
                "把若干本地文件/文件夹软删除到回收站(.misc-trash,可恢复)。此操作需用户确认。",
                obj(ARG_PATHS to strArray("要软删除的本地绝对路径列表"), required = listOf(ARG_PATHS)),
            ),
        ),
    )

    fun spec(name: String): Spec? = all.firstOrNull { it.name == name }

    /** 送给模型的所有工具定义 */
    fun toolDefs(): List<AiTool> = all.map { it.tool }

    /** 解析工具调用参数(arguments 为 JSON 字符串);非法 JSON → 空对象(容错) */
    fun parseArgs(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    // ---- JSON Schema 小工具 ----

    private fun obj(vararg props: Pair<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    private fun str(desc: String) =
        buildJsonObject { put("type", "string"); put("description", desc) }

    private fun int(desc: String) =
        buildJsonObject { put("type", "integer"); put("description", desc) }

    private fun bool(desc: String) =
        buildJsonObject { put("type", "boolean"); put("description", desc) }

    private fun strArray(desc: String) = buildJsonObject {
        put("type", "array")
        put("description", desc)
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun enumStr(desc: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", desc)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}

// ---- 参数安全读取(JsonElement 可能是任意类型,统一走 as? + contentOrNull) ----

fun JsonObject.optString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.optStringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        .orEmpty()

fun JsonObject.optInt(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

fun JsonObject.optBool(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default
