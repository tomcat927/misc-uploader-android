package com.tomcat927.miscuploader.data

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * root 桥命令输出的纯逻辑解析器(无 Android 依赖,单测覆盖)。
 *
 * toybox `ls -lA` 固定列序:perms links owner group size date time name…
 * （date=YYYY-MM-DD、time=HH:MM；文件名可含空格,取 limit=8 的尾段整段）；
 * symlink 名字带 " -> target" 尾巴,展示名截掉。GNU ls 等其它格式解析失败 → mtime 落回
 * fallbackTimeMs(与分享路径"无可靠 mtime 按当下归类"同语义)。
 */
object LsParser {

    /** 解析 ls -lA 单行;total 行/解析不了的行返回 null(调用方跳过) */
    fun parseLsLine(line: String, fallbackTimeMs: Long): RootEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("total ")) return null
        val fields = trimmed.split(Regex("\\s+"), limit = 8)
        if (fields.size < 8) return null
        val type = fields[0].firstOrNull() ?: return null
        if (type !in "-dlbcps") return null
        val size = fields[4].toLongOrNull() ?: return null
        // 日期/时间段形状不对(如 GNU ls 列序不同)→ 整行不可信,丢弃而非产出垃圾条目
        if (!DATE_RE.matches(fields[5]) || !TIME_RE.matches(fields[6])) return null
        val name = fields[7].substringBefore(" ->").trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        return RootEntry(
            path = "",
            name = name,
            isDir = type == 'd',
            size = size,
            mtimeMs = parseToyboxTime(fields[5], fields[6]) ?: fallbackTimeMs,
        )
    }

    /** 解析 `find … -exec stat -c '%s %Y %n'` 单行:size mtime(秒) 完整路径 */
    fun parseStatLine(line: String): RootEntry? {
        val fields = line.trim().split(Regex("\\s+"), limit = 3)
        if (fields.size < 3) return null
        val size = fields[0].toLongOrNull() ?: return null
        val mtimeSec = fields[1].toLongOrNull() ?: return null
        val path = fields[2]
        if (path.isEmpty()) return null
        return RootEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDir = false,
            size = size,
            mtimeMs = mtimeSec * 1000,
        )
    }

    private fun parseToyboxTime(date: String, time: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { isLenient = false }
            .parse("$date $time")?.time
    } catch (_: ParseException) {
        null
    }

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME_RE = Regex("""\d{2}:\d{2}""")
}
