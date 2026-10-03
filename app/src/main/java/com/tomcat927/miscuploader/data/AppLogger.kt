package com.tomcat927.miscuploader.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上传诊断日志(拍板:诊断能力下沉进 App——零本地环境下排查前台服务/上传问题的唯一窗口)。
 * 文件 ring 日志(filesDir/diagnostics/upload.log,超 256KB 截半),诊断页读取并复制。
 * 只存本机,不含任何敏感凭据。
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appScope: CoroutineScope,
) {

    private val logFile: File =
        File(File(context.filesDir, "diagnostics").apply { mkdirs() }, "upload.log")
    private val fileLock = Any()

    fun log(tag: String, message: String) {
        val line = "${timestamp()} $tag: $message"
        appScope.launch(Dispatchers.IO) { append(line) }
    }

    private fun append(line: String) = synchronized(fileLock) {
        runCatching {
            if (logFile.exists() && logFile.length() > MAX_BYTES) {
                val text = logFile.readText()
                logFile.writeText(text.substring(text.length / 2))
            }
            logFile.appendText(line + "\n")
        }
    }

    /** 读日志尾部(诊断页展示) */
    suspend fun readRecent(): String = withContext(Dispatchers.IO) {
        runCatching {
            if (!logFile.exists()) return@runCatching "（暂无日志）"
            val text = logFile.readText()
            if (text.length <= TAIL_CHARS) text else text.substring(text.length - TAIL_CHARS)
        }.getOrDefault("（读取失败）")
    }

    private fun timestamp(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    private companion object {
        const val MAX_BYTES = 256 * 1024L
        const val TAIL_CHARS = 16 * 1024
    }
}

/** 系统级诊断:Android 11+ 历史退出原因(排查前台服务被谁杀掉的唯一官方渠道) */
object SystemDiagnostics {

    fun exitInfo(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "需要 Android 11 及以上"
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .joinToString("\n") { info ->
                    val time = fmt.format(Date(info.timestamp))
                    val reason = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "崩溃"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户/系统请求停止"
                        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死(常见于厂商后台清理)"
                        ApplicationExitInfo.REASON_OTHER -> "其他"
                        else -> "code=${info.reason}"
                    }
                    "[$time] $reason${info.description?.let { " · $it" } ?: ""}"
                }
                .ifEmpty { "暂无退出记录" }
        }.getOrDefault("获取失败")
    }
}
