package com.tomcat927.miscuploader.ui.home

/**
 * 浏览过滤分类(拍板 2026-10-05,MT 过滤同款):
 * 目录恒显示不受过滤(否则过滤态没法导航);与隐藏文件开关叠加生效。
 * 与 ui.viewer.FileKind(查看路由)是两套枚举——浏览分类更细,互不依赖。
 */
enum class FileCategory(val label: String, val exts: Set<String>) {
    ALL("全部", emptySet()),
    IMAGE("图片", setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg")),
    VIDEO("视频", setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "flv", "wmv", "ts")),
    AUDIO("音频", setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")),
    DOC(
        "文档",
        setOf(
            "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "log",
            "json", "xml", "yaml", "yml", "html", "ini", "conf", "cfg", "properties", "epub",
        ),
    ),
    ARCHIVE("压缩包", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")),
    APK("安装包", setOf("apk", "apks", "xapk")),
    OTHER("其他", emptySet());

    companion object {
        fun of(name: String): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.exts } ?: OTHER
        }
    }
}

/** 列表排序(拍板 2026-10-05:名称/大小/时间 × 升降;目录恒优先) */
enum class SortField {
    NAME,
    SIZE,
    TIME,
}

data class SortSpec(val field: SortField, val asc: Boolean = true) {
    fun label(): String = when (field) {
        SortField.NAME -> "按名称"
        SortField.SIZE -> "按大小"
        SortField.TIME -> "按时间"
    } + if (asc) " ↑" else " ↓"
}
