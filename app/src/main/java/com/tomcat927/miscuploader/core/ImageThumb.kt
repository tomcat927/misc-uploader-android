package com.tomcat927.miscuploader.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 本地图片下采样为 JPEG base64(供多模态建议)。
 * 控体积与隐私:最长边 ≤ MAX_EDGE、JPEG 质量 70;失败返回 null(调用方跳过该图)。
 * 调用方须在 IO 调度器上执行(解码是磁盘 + CPU 密集)。
 */
object ImageThumb {
    private const val MAX_EDGE = 512
    private const val QUALITY = 70

    fun encode(file: File): AiImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bmp.recycle()
        AiImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
    }.getOrNull()
}
