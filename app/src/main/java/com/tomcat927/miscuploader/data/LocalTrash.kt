package com.tomcat927.miscuploader.data

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地软删除(拍板 2026-10-15):移到 `<存储根>/.misc-trash/<时间戳>/<相对路径>`。
 * 镜像原目录结构,故「回收站」可原样恢复;不立即释放空间。
 */
object LocalTrash {

    const val DIR = ".misc-trash"

    fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** @return 成功移入回收站的数量 */
    fun moveToTrash(paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        val root = storageRoot()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val trash = File(File(root, DIR), stamp)
        var moved = 0
        paths.forEach { p ->
            val src = File(p)
            if (!src.exists()) return@forEach
            val rel = if (src.absolutePath.startsWith(root)) {
                src.absolutePath.removePrefix(root).trimStart('/')
            } else {
                src.name
            }
            val dst = File(trash, rel)
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) moved++
        }
        return moved
    }
}
