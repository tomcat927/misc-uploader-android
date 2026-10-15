package com.tomcat927.miscuploader.data

import android.content.Context
import android.os.Build
import com.tomcat927.miscuploader.core.RemoteStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程诊断日志(对齐 notion-app-android 的做法,复用本 App 已有的 OpenList 连接):
 * 把脱敏后的诊断快照上传到 OpenList,排查者直接从服务器取回,免去手动复制粘贴。
 *
 * 目标路径:`<远程根>/_logs/install-<installId>/diagnostic-<yyyyMMdd-HHmmss>.txt`
 * (`<远程根>` 即本 App 账号 base_path 之下,磁盘上对应 /opt/misc/_logs/...)
 */
@Singleton
class RemoteLogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val logger: AppLogger,
) {

    /** 上传当前诊断快照,返回远程路径 */
    suspend fun upload(): String = withContext(Dispatchers.IO) {
        val client = ensureClient()
        val now = Date()
        val dir = "$LOG_DIR/install-${installId()}"
        val remotePath = "$dir/diagnostic-${stamp(now)}.txt"
        val tmp = File(context.cacheDir, "diagnostic-${stamp(now)}.txt")
        try {
            tmp.writeText(buildSnapshot(now))
            client.mkdirp(dir)
            client.upload(tmp, remotePath)
        } finally {
            runCatching { tmp.delete() }
        }
        logger.log("diag", "诊断日志已上传：$remotePath")
        remotePath
    }

    private suspend fun ensureClient(): RemoteStorage {
        connection.clientOrNull()?.let { return it }
        val cfg = settings.loadDecryptedOnce()
            ?: throw IllegalStateException("未配置服务器连接（设置 → 服务器）")
        connection.connect(cfg)
        return connection.clientOrNull()
            ?: throw IllegalStateException("连接服务器失败，请先在设置页连接成功")
    }

    private suspend fun buildSnapshot(now: Date): String = buildString {
        appendLine("misc-uploader-android 诊断日志")
        appendLine("生成时间：${iso(now)}")
        appendLine("版本：${versionName()}")
        appendLine(
            "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                "（API ${Build.VERSION.SDK_INT}）",
        )
        appendLine()
        appendLine("=== 上传日志 ===")
        appendLine(redact(logger.readAll()))
        appendLine()
        appendLine("=== 最近退出原因 ===")
        appendLine(SystemDiagnostics.exitInfo(context))
    }

    /** 轻量脱敏:防日志里混入 token/密码类字段(诊断日志本就承诺不含凭据,这里兜底) */
    private fun redact(input: String): String = input
        .replace(Regex("(?i)(authorization|token|password|cookie)\\s*[:=]\\s*[^\\s,;]+")) { m ->
            "${m.groupValues[1]}=***"
        }
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer ***")

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun installId(): String {
        val prefs = context.getSharedPreferences("remote_log", Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { if (it.isNotEmpty()) return it }
        val id = (1..6).joinToString("") { "%02x".format((0..255).random()) }
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun stamp(d: Date): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(d)

    private fun iso(d: Date): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(d)

    private companion object {
        /** 相对 App 账号 base_path 的日志目录(隐藏式命名,尽量少出现在相册里) */
        const val LOG_DIR = "/_logs"
        const val KEY_INSTALL_ID = "install_id"
    }
}
