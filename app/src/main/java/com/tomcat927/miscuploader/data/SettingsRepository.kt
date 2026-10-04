package com.tomcat927.miscuploader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 上传模式(拍板 A2,对齐桌面端两种模式) */
enum class UploadMode(val label: String) {
    /** 手动:上传到所选目标目录 */
    MANUAL("手动目录"),

    /** 按日期自动:按每个文件自身修改时间归 auto/yyyy/MM */
    AUTO_DATE("按日期自动"),
}

/** 已保存的连接配置(密码密文态) */
data class StoredConfig(
    val baseUrl: String,
    val username: String,
    val hasPassword: Boolean,
)

/** 解密后的完整连接配置 */
data class ServerConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * 设置持久化(拍板:对齐桌面端交互——无保存按钮,失焦即落盘;空服务器/用户名的保存直接拒绝;
 * 密码以 Keystore AES-GCM 密文存 DataStore,永不明文落盘)。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: PasswordCrypto,
) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_CIPHER = stringPreferencesKey("password_cipher")
        val UPLOAD_MODE = stringPreferencesKey("upload_mode")
        val STARTUP_UPDATE_CHECK = booleanPreferencesKey("startup_update_check")
        val UPDATE_DISMISSED_TAG = stringPreferencesKey("update_dismissed_tag")
        val TOUCH_FOCUS = booleanPreferencesKey("touch_focus")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val PIGALLERY_BASE = stringPreferencesKey("pigallery_base")
        val UPLOAD_CONCURRENCY = intPreferencesKey("upload_concurrency")
        val UPLOAD_MAX_RETRIES = intPreferencesKey("upload_max_retries")
        val UPLOAD_WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val DEFAULT_SHARE_DIR = stringPreferencesKey("default_share_dir")
        val QUEUE_PAUSED = booleanPreferencesKey("queue_paused")
    }

    /** 上传模式流(默认手动) */
    val uploadModeFlow: Flow<UploadMode> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.UPLOAD_MODE]) {
            "auto_date" -> UploadMode.AUTO_DATE
            else -> UploadMode.MANUAL
        }
    }

    suspend fun loadUploadModeOnce(): UploadMode = uploadModeFlow.first()

    suspend fun saveUploadMode(mode: UploadMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPLOAD_MODE] = if (mode == UploadMode.AUTO_DATE) "auto_date" else "manual"
        }
    }

    /** 启动检查更新开关(拍板 2026-10-04:默认开;关闭后启动完全不发起更新源请求) */
    val startupUpdateCheckFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STARTUP_UPDATE_CHECK] ?: true
    }

    suspend fun startupUpdateCheckOnce(): Boolean = startupUpdateCheckFlow.first()

    suspend fun saveStartupUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STARTUP_UPDATE_CHECK] = enabled
        }
    }

    /** 启动弹窗被「暂不」的版本 tag(每版本只弹一次确认框) */
    suspend fun updateDismissedTagOnce(): String =
        context.dataStore.data.first()[Keys.UPDATE_DISMISSED_TAG].orEmpty()

    suspend fun saveUpdateDismissedTag(tag: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_DISMISSED_TAG] = tag
        }
    }

    /** 双栏触摸聚焦开关(拍板 2026-10-04:默认开;关闭=两栏恒单列,点击只变高亮不改布局) */
    val touchFocusFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.TOUCH_FOCUS] ?: true
    }

    suspend fun saveTouchFocus(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOUCH_FOCUS] = enabled
        }
    }

    /** 显示隐藏文件开关(拍板 2026-10-04:默认关;"." 前缀=隐藏,本地/远程同规则) */
    val showHiddenFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_HIDDEN] ?: false
    }

    suspend fun saveShowHidden(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOW_HIDDEN] = enabled
        }
    }

    /** PiGallery2 地址(D1 拍板:可选,空 = 不显示相册深链按钮;由用户运行时配置) */
    val pigalleryBaseFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PIGALLERY_BASE].orEmpty()
    }

    suspend fun savePigalleryBase(url: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PIGALLERY_BASE] = url.trim()
        }
    }

    // ---- 上传设置(拍板 2026-10-04;并发/最大重试改动下次队列启动生效) ----

    /** 上传并发数(默认 2,范围 1–4) */
    val uploadConcurrencyFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.UPLOAD_CONCURRENCY] ?: 2).coerceIn(1, 4)
    }

    suspend fun loadUploadConcurrencyOnce(): Int = uploadConcurrencyFlow.first()

    suspend fun saveUploadConcurrency(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPLOAD_CONCURRENCY] = value.coerceIn(1, 4)
        }
    }

    /** 最大重试次数(默认 3,范围 0–5;退避序列仍为 2s/8s/30s,超出封顶 30s) */
    val uploadMaxRetriesFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.UPLOAD_MAX_RETRIES] ?: 3).coerceIn(0, 5)
    }

    suspend fun loadUploadMaxRetriesOnce(): Int = uploadMaxRetriesFlow.first()

    suspend fun saveUploadMaxRetries(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPLOAD_MAX_RETRIES] = value.coerceIn(0, 5)
        }
    }

    /** 仅 Wi-Fi 上传(默认关;开启后蜂窝网络下队列暂停,恢复 Wi-Fi 自动续跑) */
    val wifiOnlyFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.UPLOAD_WIFI_ONLY] ?: false
    }

    suspend fun saveWifiOnly(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPLOAD_WIFI_ONLY] = enabled
        }
    }

    /** 分享接收目标目录(手动模式;默认仓库根 "/";自动归类模式不受影响) */
    val defaultShareDirFlow: Flow<String> = context.dataStore.data.map { prefs ->
        UploadPlanning.normalizeDir(prefs[Keys.DEFAULT_SHARE_DIR] ?: "/")
    }

    suspend fun loadDefaultShareDirOnce(): String = defaultShareDirFlow.first()

    suspend fun saveDefaultShareDir(dir: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SHARE_DIR] = UploadPlanning.normalizeDir(dir)
        }
    }

    /** 队列暂停(拍板 2026-10-05:持久化——进程被杀/重启后仍是暂停态;暂停=不取新任务,进行中传完为止) */
    val queuePausedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.QUEUE_PAUSED] ?: false
    }

    suspend fun saveQueuePaused(paused: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.QUEUE_PAUSED] = paused
        }
    }

    /** 密文态配置流(密码不经过此流) */
    val storedConfigFlow: Flow<StoredConfig> = context.dataStore.data.map { prefs ->
        StoredConfig(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            username = prefs[Keys.USERNAME].orEmpty(),
            hasPassword = !prefs[Keys.PASSWORD_CIPHER].isNullOrEmpty(),
        )
    }

    suspend fun loadOnce(): StoredConfig =
        storedConfigFlow.first()

    /** 解密后的完整配置;服务器/用户名/密码任一缺失 = 配置不齐,返回 null */
    suspend fun loadDecryptedOnce(): ServerConfig? {
        val prefs = context.dataStore.data.first()
        val url = prefs[Keys.BASE_URL].orEmpty()
        val user = prefs[Keys.USERNAME].orEmpty()
        val cipher = prefs[Keys.PASSWORD_CIPHER] ?: return null
        if (url.isEmpty() || user.isEmpty()) return null
        return runCatching { ServerConfig(url, user, crypto.decrypt(cipher)) }.getOrNull()
    }

    /**
     * 保存(失焦即存)。
     * @param password null = 沿用已存密码(密码框留空的语义);空串 = 不保存密码
     * @return true = 落盘;false = 校验拒绝(空服务器/用户名不落盘)
     */
    suspend fun save(baseUrl: String, username: String, password: String?): Boolean {
        val url = baseUrl.trim()
        val user = username.trim()
        if (url.isEmpty() || user.isEmpty()) return false
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = url
            prefs[Keys.USERNAME] = user
            if (password != null && password.isNotEmpty()) {
                prefs[Keys.PASSWORD_CIPHER] = crypto.encrypt(password)
            } else if (password?.isEmpty() == true) {
                // 显式清空密码
                prefs.remove(Keys.PASSWORD_CIPHER)
            }
            // password == null:沿用已存
        }
        return true
    }

    /** 「显示」按钮:按需取回真实密码(掩码态不经过此方法) */
    suspend fun revealPassword(): String? {
        val cipher = context.dataStore.data.first()[Keys.PASSWORD_CIPHER] ?: return null
        return runCatching { crypto.decrypt(cipher) }.getOrNull()
    }

    companion object {
        /** 掩码回显:与桌面端同语义,已存密码显示 8 位掩码点 */
        const val PASSWORD_MASK = "••••••••"

        /** 地址归一化:无 scheme 补 https://,去尾部斜杠 */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.trimEnd('/')
        }
    }
}
