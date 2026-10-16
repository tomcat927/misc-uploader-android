package com.tomcat927.miscuploader.data

import android.os.Environment
import com.tomcat927.miscuploader.core.AiImage
import com.tomcat927.miscuploader.core.AiToolCall
import com.tomcat927.miscuploader.core.AiTools
import com.tomcat927.miscuploader.core.ImageThumb
import com.tomcat927.miscuploader.core.optBool
import com.tomcat927.miscuploader.core.optInt
import com.tomcat927.miscuploader.core.optString
import com.tomcat927.miscuploader.core.optStringList
import com.tomcat927.miscuploader.data.db.PendingCleanupDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 工具执行结果:文本结果 + 需注入给模型的图片(OpenAI 的 tool 消息是纯文本,图片另走 user 消息) */
data class ToolOutcome(val text: String, val images: List<AiImage> = emptyList())

/**
 * AI agent 只读工具执行层(拍板 2026-10-16):把模型的 tool_call 映射到 app 既有能力。
 * 只读工具后台自动执行;执行失败返回可读文本而非抛异常,让模型知道并自行纠正。
 */
@Singleton
class AiToolRunner @Inject constructor(
    private val connection: ConnectionManager,
    private val rootShell: RootShell,
    private val pendingCleanupDao: PendingCleanupDao,
    private val logger: AppLogger,
) {

    private val maxRepoDirs = 200
    private val maxRepoDepth = 4
    private val maxListEntries = 200
    private val maxImages = 8

    suspend fun run(call: AiToolCall): ToolOutcome = withContext(Dispatchers.IO) {
        val args = AiTools.parseArgs(call.arguments)
        runCatching {
            when (call.name) {
                AiTools.LIST_LOCAL -> listLocal(args.optString(AiTools.ARG_PATH))
                AiTools.READ_IMAGES -> readImages(args.optStringList(AiTools.ARG_PATHS))
                AiTools.LIST_REPO -> listRepo(args.optString(AiTools.ARG_PATH))
                AiTools.REPO_TREE -> repoTree(args.optInt(AiTools.ARG_DEPTH, 2))
                AiTools.SEARCH_LOCAL -> searchLocal(
                    query = args.optString(AiTools.ARG_QUERY),
                    path = args.optString(AiTools.ARG_PATH),
                    recursive = args.optBool(AiTools.ARG_RECURSIVE, true),
                )

                AiTools.LIST_PENDING_CLEANUP -> listPending()
                else -> ToolOutcome("错误：工具 ${call.name} 不可用。")
            }
        }.getOrElse { e ->
            logger.log("ai", "工具 ${call.name} 执行失败：${e.message}")
            ToolOutcome("工具 ${call.name} 执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- 只读工具实现 ----

    private suspend fun listLocal(path: String?): ToolOutcome {
        if (path.isNullOrBlank()) return ToolOutcome("错误：缺少 path 参数。")
        val dir = File(path)
        if (dir.isDirectory && dir.canRead()) {
            val entries = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() }),
            ).orEmpty()
            return ToolOutcome(renderLocal(path, entries))
        }
        // 受限目录(如 Android/data)回落 root 桥
        return runCatching {
            val rootEntries = rootShell.list(path)
            val lines = rootEntries.take(maxListEntries).map { e ->
                (if (e.isDir) "DIR  " else "FILE ") + e.name + " (" + e.size + " B)"
            }
            ToolOutcome("目录 $path（${rootEntries.size} 项，root 视角）:\n" + lines.joinToString("\n").ifBlank { "（空）" })
        }.getOrElse { ToolOutcome("无法列出 $path：${it.message ?: "权限不足"}") }
    }

    private fun renderLocal(path: String, entries: List<File>): String {
        val lines = entries.take(maxListEntries).map { e ->
            (if (e.isDirectory) "DIR  " else "FILE ") + e.name + " (" + e.length() + " B)"
        }
        return "目录 $path（${entries.size} 项）:\n" + lines.joinToString("\n").ifBlank { "（空）" }
    }

    private fun readImages(paths: List<String>): ToolOutcome {
        if (paths.isEmpty()) return ToolOutcome("错误：缺少 paths 参数。")
        val images = mutableListOf<AiImage>()
        val failed = mutableListOf<String>()
        paths.take(maxImages).forEach { p ->
            val img = ImageThumb.encode(File(p))
            if (img != null) images += img else failed += p
        }
        val text = buildString {
            append("已附上 ${images.size} 张图片")
            if (paths.size > maxImages) append("（超过 $maxImages 张，仅取前 $maxImages 张）")
            append("。")
            if (failed.isNotEmpty()) append(" 无法读取（非图片或读取失败）：${failed.joinToString("、")}")
        }
        return ToolOutcome(text, images)
    }

    private suspend fun listRepo(path: String?): ToolOutcome {
        val client = connection.clientOrNull() ?: return ToolOutcome("错误：未连接仓库，无法列出远程目录。")
        val p = path?.takeIf { it.isNotBlank() } ?: "/"
        val entries = client.list(p, refresh = true)
        val lines = entries.take(maxListEntries).map { e -> (if (e.isDir) "DIR  " else "FILE ") + e.name }
        return ToolOutcome("远程目录 $p（${entries.size} 项）:\n" + lines.joinToString("\n").ifBlank { "（空）" })
    }

    private suspend fun repoTree(depth: Int): ToolOutcome {
        val client = connection.clientOrNull() ?: return ToolOutcome("错误：未连接仓库。")
        val d = depth.coerceIn(1, maxRepoDepth)
        val out = mutableListOf<String>()
        suspend fun walk(path: String, level: Int) {
            if (level > d || out.size >= maxRepoDirs) return
            val dirs = runCatching { client.list(path).filter { it.isDir }.map { it.name } }.getOrDefault(emptyList())
            dirs.forEach { n ->
                if (out.size >= maxRepoDirs) return
                val child = if (path == "/") "/$n" else "$path/$n"
                out += child
                walk(child, level + 1)
            }
        }
        walk("/", 1)
        return ToolOutcome("仓库目录（${out.size} 个）:\n" + out.joinToString("\n").ifBlank { "（空）" })
    }

    private suspend fun searchLocal(query: String?, path: String?, recursive: Boolean): ToolOutcome {
        if (query.isNullOrBlank()) return ToolOutcome("错误：缺少 query 参数。")
        val base = path?.takeIf { it.isNotBlank() } ?: defaultRoot()
        val regex = globToRegex(query)
        val matches = runCatching {
            val baseFile = File(base)
            val seq = if (recursive) {
                baseFile.walkTopDown()
            } else {
                baseFile.listFiles()?.asSequence() ?: emptySequence()
            }
            seq.filter { it.isFile && regex.matches(it.name) }
                .take(maxListEntries)
                .map { it.absolutePath to it.length() }
                .toList()
        }.getOrNull()
        if (matches != null) {
            return ToolOutcome(
                "在 $base 搜索「$query」命中 ${matches.size} 个文件:\n" +
                    matches.joinToString("\n") { (p, s) -> "$p ($s B)" }.ifBlank { "（无）" },
            )
        }
        // root 回落
        return runCatching {
            val rootMatches = rootShell.searchFiles(base, recursive, query)
            ToolOutcome(
                "在 $base 搜索「$query」命中 ${rootMatches.size} 个文件（root 视角）:\n" +
                    rootMatches.take(maxListEntries).joinToString("\n") { "${it.path} (${it.size} B)" }
                        .ifBlank { "（无）" },
            )
        }.getOrElse { ToolOutcome("搜索失败：${it.message ?: "权限不足"}") }
    }

    private suspend fun listPending(): ToolOutcome {
        val items = pendingCleanupDao.observeAll().first()
        if (items.isEmpty()) return ToolOutcome("待清理列表为空。")
        val lines = items.take(maxListEntries).map { "${it.localPath}（已归档到 ${it.remotePath}）" }
        return ToolOutcome("待清理（${items.size} 项）:\n" + lines.joinToString("\n"))
    }

    private fun defaultRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** 通配符转正则:* → .*  ? → . ;忽略大小写(与本地搜索语义一致) */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
