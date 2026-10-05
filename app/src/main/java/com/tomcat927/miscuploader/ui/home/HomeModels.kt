package com.tomcat927.miscuploader.ui.home

/** 双栏侧(拍板:左=本地手机文件,右=远程 /misc) */
enum class Side { LEFT, RIGHT }

/** 统一的文件项模型:本地 File 与远程 FsEntry 各自映射(时间在映射侧格式化成展示文本) */
data class FileItem(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedText: String,
    val mtimeMs: Long? = null,
)

/** 单侧浏览器状态 */
data class BrowserState(
    val path: String,
    val entries: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 左栏当前目录经 root 桥列出(拍板 2026-10-05);右栏恒 false */
    val viaRoot: Boolean = false,
)
