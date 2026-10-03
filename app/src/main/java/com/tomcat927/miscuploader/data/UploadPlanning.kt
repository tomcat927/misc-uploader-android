package com.tomcat927.miscuploader.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 上传目标规划(拍板 A2:自动归类按每个文件自身 mtime → auto/yyyy/MM,本地时区) */
object UploadPlanning {

    const val AUTO_ROOT = "auto"

    /** 按修改时间算自动归类目录,如 auto/2026/10 */
    fun autoDirFor(mtimeMs: Long): String =
        AUTO_ROOT + "/" + SimpleDateFormat("yyyy/MM", Locale.CHINA).format(Date(mtimeMs))

    /** 目录归一:以 "/" 开头、去尾部斜杠 */
    fun normalizeDir(dir: String): String {
        val d = dir.trim().trimEnd('/')
        return when {
            d.isEmpty() || d == "/" -> "/"
            d.startsWith("/") -> d
            else -> "/$d"
        }
    }

    /** 拼完整远程路径:保证以 "/" 开头 */
    fun joinRemotePath(dir: String, rel: String): String {
        val d = dir.trim().trimEnd('/')
        val joined = if (d.isEmpty()) rel else "$d/$rel"
        return if (joined.startsWith("/")) joined else "/$joined"
    }
}
