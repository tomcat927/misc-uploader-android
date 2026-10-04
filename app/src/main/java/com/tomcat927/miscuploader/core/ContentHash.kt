package com.tomcat927.miscuploader.core

import java.io.File
import java.security.MessageDigest

/** 内容级 SHA-256(A1 拍板:去重的唯一依据,同 hash 任何目标目录都跳过) */
object ContentHash {

    /** 流式计算(64KB 分块,内存恒定);返回 64 位小写 hex */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
