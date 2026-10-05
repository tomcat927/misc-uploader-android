package com.tomcat927.miscuploader.ui.home

import java.time.LocalDate
import java.time.ZoneId

/**
 * 全局搜索会话状态(拍板 2026-10-05,MT 搜索同款裁剪):
 * 范围=当前目录起(可递归)、关键词通配符、类型、时间范围;**仅本地侧(含 root 桥)**,远程栏不做;
 * 结果页临时多选直接入队(不动"多选不跨目录"状态机),单条点击=跳转定位。
 */
data class LocalSearchState(
    val scopeDir: String,
    val viaRoot: Boolean,
    val query: String = "",
    val recursive: Boolean = true,
    val category: FileCategory = FileCategory.ALL,
    val timeRange: SearchTimeRange = SearchTimeRange.ANY,
    val phase: Phase = Phase.INPUT,
    val scanned: Int = 0,
    val results: List<SearchHit> = emptyList(),
) {
    enum class Phase { INPUT, RUNNING, DONE }
}

/** 单条搜索结果:文件(搜索只出文件不出目录,上传语义所需;边界记 DESIGN) */
data class SearchHit(
    val path: String,
    val name: String,
    val size: Long,
    val mtimeMs: Long,
)

/** 时间过滤(MT 同款相对范围;今天=本地零点起) */
enum class SearchTimeRange(val label: String) {
    ANY("不限"),
    TODAY("今天"),
    WEEK("近 7 天"),
    MONTH("近 30 天");

    /** 早于该时刻的条目被过滤;null = 不过滤 */
    fun sinceMs(now: Long): Long? = when (this) {
        ANY -> null
        TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        WEEK -> now - 7L * 86_400_000
        MONTH -> now - 30L * 86_400_000
    }
}

/**
 * MT 式名字匹配(纯逻辑,单测覆盖):含 * ? 按通配符整名匹配,否则按包含子串;一律忽略大小写。
 * 其余正则元字符按字面处理(Regex.escape),防用户输入 . 等字符被当正则解释。
 */
object NameMatching {

    fun matches(pattern: String, name: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return true
        if (!p.contains('*') && !p.contains('?')) return name.contains(p, ignoreCase = true)
        val regex = buildString {
            for (c in p) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(name)
    }
}
