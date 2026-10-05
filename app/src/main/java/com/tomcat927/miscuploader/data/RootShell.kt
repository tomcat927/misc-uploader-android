package com.tomcat927.miscuploader.data

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileInputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** root 视角下的文件条目;path = 完整路径(列表场景由调用方拼接) */
data class RootEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMs: Long,
)

/**
 * root 桥(拍板 2026-10-05):经 libsu 常驻 su 会话列目录/读文件,绕过 Android/data 访问限制
 * ——「所有文件访问」不覆盖其它 app 的 Android/data、Android/obb,SAF 树授权在 Android 13+ 也被封,
 * root(uid 0)是唯一无系统版本分岔的通路。仅在左栏 File.listFiles() 失败时回落使用;
 * 读文件 = cat 流式拷缓存,之后入队与分享路径同构(队列/重试/归类零改动)。
 *
 * 已知边界:文件名含换行会破坏行协议(手机下载场景罕见);symlink 一律按文件处理;
 * 首次调用触发 Magisk 授权框,拒绝后本进程内 isAppGrantedRoot 恒 false(重试需重启 app 或 Magisk 内改授权)。
 */
@Singleton
class RootShell @Inject constructor(private val logger: AppLogger) {

    /** 探测并确认 root;未建会话时会先建(触发授权框,阻塞到用户选择),拒绝/无 su 返回 false */
    suspend fun ensureAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (Shell.isAppGrantedRoot() == true) return@withContext true
        runCatching {
            // 默认 builder 在无 su 时静默回落非 root shell,必须以 isAppGrantedRoot 的结果为准
            Shell.getShell()
            (Shell.isAppGrantedRoot() == true).also {
                logger.log("root", "root 探测：$it")
            }
        }.getOrElse {
            logger.log("root", "root 探测失败：${it.message}")
            false
        }
    }

    /** ls -lA 列单层目录(toybox 固定格式,解析见 LsParser) */
    suspend fun list(dir: String): List<RootEntry> = withContext(Dispatchers.IO) {
        val result = Shell.cmd("ls -lA ${quote(dir)}").exec()
        if (!result.isSuccess) {
            throw IOException(result.err.firstOrNull() ?: "ls 退出码 ${result.code}")
        }
        val now = System.currentTimeMillis()
        result.out.mapNotNull { LsParser.parseLsLine(it, now) }
            .map { it.copy(path = "${dir.trimEnd('/')}/${it.name}") }
    }

    /** 递归列出目录下全部文件(find + 逐项 stat;常驻会话内,批量百文件级开销秒内) */
    suspend fun listFilesRecursive(dir: String): List<RootEntry> = withContext(Dispatchers.IO) {
        val result = Shell.cmd("find ${quote(dir)} -type f -exec stat -c '%s %Y %n' {} \\;").exec()
        if (!result.isSuccess) {
            throw IOException(result.err.firstOrNull() ?: "find 退出码 ${result.code}")
        }
        result.out.mapNotNull { LsParser.parseStatLine(it) }
    }

    /**
     * 按名字找文件(全局搜索用):find -iname 大小写不敏感通配匹配 + stat 带出大小/mtime。
     * 无通配符的关键词包成 *kw*;一次性命令,无逐条进度(拍板 2026-10-05 已知边界)。
     */
    suspend fun searchFiles(dir: String, recursive: Boolean, pattern: String): List<RootEntry> =
        withContext(Dispatchers.IO) {
            val trimmed = pattern.trim()
            val glob = if ('*' in trimmed || '?' in trimmed) trimmed else "*$trimmed*"
            val depth = if (recursive) "" else " -maxdepth 1"
            val cmd = "find ${quote(dir)}$depth -type f -iname ${quote(glob)} -exec stat -c '%s %Y %n' {} \\;"
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                throw IOException(result.err.firstOrNull() ?: "find 退出码 ${result.code}")
            }
            result.out.mapNotNull { LsParser.parseStatLine(it) }
        }

    /**
     * root 流式读取文件到本地缓存(SuFileInputStream,io 模块——core 的 Job API 无 stdout 转 OutputStream)。
     * expectedSize 来自列目录结果,不符视为读取不完整并删除残件。
     */
    suspend fun readToFile(path: String, expectedSize: Long, dest: File): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        try {
            dest.outputStream().use { out ->
                SuFileInputStream.open(path).use { input -> input.copyTo(out) }
            }
        } catch (e: IOException) {
            dest.delete()
            throw IOException("读取失败：${e.message ?: e.javaClass.simpleName}")
        }
        val actual = dest.length()
        if (actual != expectedSize) {
            dest.delete()
            throw IOException("读取不完整（期望 $expectedSize B，实际 $actual B）")
        }
        actual
    }

    /** 单引号包裹,内部单引号按 POSIX 规则转义(路径来自列目录结果,可能有任意字符) */
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
