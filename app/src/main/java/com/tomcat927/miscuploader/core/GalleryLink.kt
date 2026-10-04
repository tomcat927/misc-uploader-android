package com.tomcat927.miscuploader.core

import java.net.URLEncoder

/**
 * PiGallery2 深链(D1 拍板):相册根 = 热层 /misc 根,路径零映射直用。
 * URL 格式对照 pigallery2 源码核实(坑 5):app.routing.ts 的 galleryMatcher 以
 * 路由段 /gallery/<dir> 接收目录(route.params.directory),路径中的 "/" 须编码为
 * %2F 才构成单段;base 尾部已带 /gallery 时自动去重。
 */
object GalleryLink {

    /** base 为空 = 未配置;远程根目录 = 相册首页;其余 = /gallery/<%2F 编码相对路径> */
    fun forDir(baseUrl: String?, remotePath: String): String? {
        var base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (base.isEmpty()) return null
        if (base.endsWith("/gallery")) base = base.removeSuffix("/gallery")
        val rel = remotePath.trim('/')
        if (rel.isEmpty()) return "$base/gallery"
        val encoded = rel.split('/').filter { it.isNotEmpty() }
            .joinToString("%2F") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$base/gallery/$encoded"
    }
}
