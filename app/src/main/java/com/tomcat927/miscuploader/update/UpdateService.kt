package com.tomcat927.miscuploader.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 应用内热更新(拍板 2026-10-04,移植 ncm-cloud-player 同款实现):
 * latest.json 双源检查(gh-proxy 优先 → GitHub 直连回退) → 版本比较 → APK 下载(进度回调)
 * → SHA-256 校验 → FileProvider 安装 Intent。
 * latest.json 由 release.yml 发布(含 version_code/apk/github_apk/sha256)。
 */
@Singleton
class UpdateService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "UpdateService"
        private const val OWNER = "tomcat927"
        private const val REPO = "misc-uploader-android"
        private const val PROXY_PREFIX = "https://gh-proxy.com/"
    }

    data class UpdateInfo(
        val tagName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val fallbackDownloadUrl: String,
        val checksumUrl: String,
        val fallbackChecksumUrl: String,
        val releaseUrl: String,
        val releaseNotes: String?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val manifestUrls = listOf(
        "${PROXY_PREFIX}https://github.com/$OWNER/$REPO/releases/latest/download/latest.json",
        "https://github.com/$OWNER/$REPO/releases/latest/download/latest.json",
    )

    private val apiUrl = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = currentVersionCode()
        // 双源都不可达 → 明确报错(而不是误报"已是最新");网络瞬断由 readText 内部重试兜底
        val info = checkFromManifest()
            ?: checkFromGitHubApi()
            ?: throw IllegalStateException("无法连接更新源（gh-proxy 与 GitHub 均不可达，请检查网络或代理）")
        Log.i(TAG, "current=$current latest=${info.versionCode}")
        if (info.versionCode > current) info else null
    }

    private fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private suspend fun checkFromManifest(): UpdateInfo? {
        for (url in manifestUrls) {
            val body = readText(url) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val versionCode = json.optLong("version_code", -1L)
            val apk = json.optString("apk")
            val githubApk = json.optString("github_apk")
            if (versionCode <= 0 || apk.isEmpty() || githubApk.isEmpty()) continue
            return UpdateInfo(
                tagName = json.optString("tag_name"),
                versionCode = versionCode,
                downloadUrl = apk,
                fallbackDownloadUrl = githubApk,
                checksumUrl = json.optString("apk_sha256"),
                fallbackChecksumUrl = json.optString("github_apk_sha256"),
                releaseUrl = json.optString("release_url"),
                releaseNotes = json.optString("release_notes", null).takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    private suspend fun checkFromGitHubApi(): UpdateInfo? {
        val body = readText(apiUrl, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to REPO))
            ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tagName = json.optString("tag_name")
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var checksumUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk")) apkUrl = url
            else if (name.endsWith(".apk.sha256")) checksumUrl = url
        }
        if (apkUrl.isEmpty()) return null
        val versionCode = versionCodeFromTag(tagName) ?: return null
        return UpdateInfo(
            tagName = tagName,
            versionCode = versionCode,
            downloadUrl = "$PROXY_PREFIX$apkUrl",
            fallbackDownloadUrl = apkUrl,
            checksumUrl = "$PROXY_PREFIX$checksumUrl",
            fallbackChecksumUrl = checksumUrl,
            releaseUrl = json.optString("html_url"),
            releaseNotes = json.optString("body", null).takeIf { it.isNotBlank() },
        )
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: suspend (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "开始下载 APK: ${info.tagName}")
            val dir = File(context.cacheDir, "apk_updates").apply { mkdirs() }
            val file = File(dir, "misc-uploader-update.apk")
            if (file.exists()) file.delete()

            downloadTo(info.downloadUrl, file, onProgress)
                ?: downloadTo(info.fallbackDownloadUrl, file, onProgress)
                ?: throw IllegalStateException("APK 下载失败")

            val expected = readChecksum(info.checksumUrl) ?: readChecksum(info.fallbackChecksumUrl)
            if (expected != null) {
                val actual = sha256(file)
                Log.i(TAG, "APK SHA-256: expected=$expected, actual=$actual")
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 校验失败")
                }
            } else {
                Log.w(TAG, "未获取到 SHA-256 校验值,跳过校验")
            }
            file
        }

    fun createInstallIntent(file: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private suspend fun downloadTo(url: String, target: File, onProgress: suspend (Float) -> Unit): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "下载响应失败: code=${response.code}, url=$url")
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                val input = body.byteStream()
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            target.exists() && target.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "下载失败: $url", e)
            false
        }
    }

    private suspend fun readText(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $url", e)
            null
        }
    }

    private suspend fun readChecksum(url: String): String? {
        val text = readText(url) ?: return null
        val value = text.trim().split(Regex("\\s+")).firstOrNull()
        return value?.takeIf { it.length == 64 }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** tag 形如 v0.1.0-20261003235946 → 中国时区 epoch(与 CI 的 versionCode 同源) */
    private fun versionCodeFromTag(tag: String): Long? {
        val match = Regex("-(\\d{8})(\\d{2})(\\d{2})(\\d{2})$").find(tag) ?: return null
        val (date, hour, minute, second) = match.destructured
        val beijing = java.time.LocalDateTime.of(
            date.substring(0, 4).toInt(),
            date.substring(4, 6).toInt(),
            date.substring(6, 8).toInt(),
            hour.toInt(), minute.toInt(), second.toInt(),
        )
        return beijing.atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond()
    }
}
